#include <oboe/Oboe.h>

#include <android/api-level.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <vector>

// 环形缓冲与 USB 独占共用一份（语义必须完全一致，见 `pcm-ring.h` 顶部）
#include "pcm-ring.h"

namespace
{

thread_local int g_lastError = 0;

/**
 * 字节口径的 SPSC 环形缓冲。
 *
 * 位置用**单调递增的字节偏移**（不取模），只在拷贝时换算成数组下标 ——
 * 这样 `容量 - (写位置 - 读位置)` 天然正确，不需要处理 wrap 的边界判断
 * （手写 wrap 判断是这类代码最常见的越界来源）。
 */
using annie::ByteRing;

/** 一条 Oboe 流 + 它自己的环形缓冲。句柄就是它的指针。 */
class OboeStream : public oboe::AudioStreamDataCallback,
                   public oboe::AudioStreamErrorCallback
{
public:
    // ── 回调线程 ──────────────────────────────────────────────────────────────

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* /* stream */, void* audioData, int32_t numFrames ) override
    {
        if( audioData == nullptr || numFrames <= 0 )
        {
            return( oboe::DataCallbackResult::Continue );
        }
        const size_t bytes = static_cast< size_t >( numFrames ) * frameBytes;
        // 暂停 = 只出静音，**不从环形缓冲取数据**：位置因此冻结，恢复后从原位接着播
        if( paused.load( std::memory_order_acquire ))
        {
            std::memset( audioData, 0, bytes );
            return( oboe::DataCallbackResult::Continue );
        }
        const size_t got = ring.read( static_cast< uint8_t* >( audioData ), static_cast< size_t >( numFrames ));
        if( got < static_cast< size_t >( numFrames ))
        {
            // 欠载：剩余填静音。**不计入 framesRead** —— 静音不是媒体时间。
            std::memset(
                static_cast< uint8_t* >( audioData ) + got * frameBytes, 0,
                ( static_cast< size_t >( numFrames ) - got ) * frameBytes );
            underruns.fetch_add( 1, std::memory_order_relaxed );
        }
        if( got > 0 )
        {
            framesRead.fetch_add( static_cast< int64_t >( got ), std::memory_order_relaxed );
            // 增益在消费侧施加：环里存未衰减的原始样本，换音量只剩一个回调周期的延迟
            const size_t channels = subslotBytes > 0 ? frameBytes / subslotBytes : 1;
            annie::pcmApplyGain(
                static_cast< uint8_t* >( audioData ), got * channels, subslotBytes,
                gainQ16.load( std::memory_order_acquire ), ditherRng );
        }
        return( oboe::DataCallbackResult::Continue );
    }

    void onErrorAfterClose( oboe::AudioStream* /* stream */, oboe::Result error ) override
    {
        g_lastError = static_cast< int >( error );
        disconnected.store( true, std::memory_order_release );
    }

    // ── 状态（写线程 / 播放线程读）─────────────────────────────────────────────

    std::shared_ptr< oboe::AudioStream > stream;
    ByteRing ring;
    std::atomic< bool > paused{ false };
    /** 每个样本的字节数（2 / 3 / 4）：`frameBytes / subslotBytes` 就是声道数 */
    size_t subslotBytes = 4;
    /** 软件增益（Q16）与它的抖动状态；都只在回调线程里读/推进 */
    std::atomic< int32_t > gainQ16{ annie::kGainUnity };
    uint32_t ditherRng = 0x9E3779B9u;
    std::atomic< bool > disconnected{ false };
    std::atomic< int64_t > framesRead{ 0 };
    std::atomic< int64_t > framesWritten{ 0 };
    std::atomic< int64_t > underruns{ 0 };
    size_t frameBytes = 4;
    int32_t sampleRate = 0;
};

/** `jjlong` ←→ `OboeStream*` */
OboeStream* asStream( jlong handle )
{
    return( reinterpret_cast< OboeStream* >( handle ));
}

constexpr int kFormatI16 = 1;
constexpr int kFormatFloat = 2;
constexpr int kFormatI24Packed = 3;

constexpr int kUnknownRate = 1;
constexpr int kUnknownFormat = 2;
constexpr int kUnknownHardwareFormat = 4;

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeOpen(
        JNIEnv* /* env */, jclass /* 静态方法 */,
        jint sampleRate, jint depth, jint channels, jboolean exclusive,
        jint deviceId, jint framesPerCallback, jint bufferCapacityFrames )
{
    g_lastError = 0;
    if( sampleRate <= 0 || channels <= 0 )
    {
        g_lastError = -1;
        return( 0 );
    }

    oboe::AudioFormat format = oboe::AudioFormat::Float;
    int depthBytes = 4;
    if( depth == 16 )
    {
        format = oboe::AudioFormat::I16;
        depthBytes = 2;
    }
    else if( depth == 24 )
    {
        format = oboe::AudioFormat::I24;
        depthBytes = 3;
    }

    int32_t capacityFrames = bufferCapacityFrames > 0 ? bufferCapacityFrames : sampleRate / 10;
    if( capacityFrames < 512 )
    {
        capacityFrames = 512;
    }

    auto* created = new OboeStream();
    created->frameBytes = static_cast< size_t >( depthBytes ) * static_cast< size_t >( channels );
    created->subslotBytes = static_cast< size_t >( depthBytes );
    created->sampleRate = sampleRate;
    // 环形缓冲给设备缓冲的 4 倍，且不少于 250ms —— 上游一次 `handleBuffer` 可能送来
    // 一大块（几十 KB），太小会让写线程频繁返回"满了"，白白增加 CPU 唤醒。
    size_t ringBytes = static_cast< size_t >( capacityFrames ) * 4 * created->frameBytes;
    const size_t ringFloor = static_cast< size_t >( sampleRate ) / 4 * created->frameBytes;
    created->ring.init( std::max( ringBytes, ringFloor ), created->frameBytes );

    oboe::AudioStreamBuilder builder;
    if( android_get_device_api_level() >= 26 )
    {
        builder.setAudioApi( oboe::AudioApi::AAudio );
    }
    if( deviceId > 0 )
    {
        builder.setDeviceId( deviceId );
    }
    builder.setDirection( oboe::Direction::Output )
        ->setUsage( oboe::Usage::Media )
        ->setContentType( oboe::ContentType::Music )
        ->setSampleRate( sampleRate )
        ->setChannelCount( static_cast< int32_t >( channels ))
        ->setFormat( format )
        ->setSharingMode( exclusive == JNI_TRUE ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared )
        ->setPerformanceMode( oboe::PerformanceMode::LowLatency )
        ->setBufferCapacityInFrames( capacityFrames )
        ->setFramesPerDataCallback( framesPerCallback > 0 ? framesPerCallback : 0 )
        ->setFormatConversionAllowed( false )
        ->setChannelConversionAllowed( false )
        ->setSampleRateConversionQuality( oboe::SampleRateConversionQuality::None )
        ->setDataCallback( created )
        ->setErrorCallback( created );

    std::shared_ptr< oboe::AudioStream > stream;
    const oboe::Result opened = builder.openStream( stream );
    if( opened != oboe::Result::OK || !stream )
    {
        g_lastError = static_cast< int >( opened );
        delete created;
        return( 0 );
    }
    created->stream = std::move( stream );

    const int32_t burst = created->stream->getFramesPerBurst();
    const int32_t deviceCapacity = created->stream->getBufferCapacityInFrames();
    int32_t want = capacityFrames;
    if( burst > 0 && want < burst * 2 )
    {
        want = burst * 2;
    }
    if( deviceCapacity > 0 && want < deviceCapacity )
    {
        want = deviceCapacity;
    }
    created->stream->setBufferSizeInFrames( want );

    const oboe::Result started = created->stream->requestStart();
    if( started != oboe::Result::OK )
    {
        g_lastError = static_cast< int >( started );
        created->stream->close();
        created->stream.reset();
        delete created;
        return( 0 );
    }

    return( reinterpret_cast< jlong >( created ));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeLastError(
        JNIEnv* /* env */, jclass /* 静态方法 */ )
{
    return( g_lastError );
}

/**
 * 写入 PCM。返回**写入的帧数**；0 = 环形缓冲满了（调用方稍后重试同一位置）；
 * 负数 = 流已断开/无效（调用方走 `onEngineFailure`）。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeWrite(
        JNIEnv* env, jclass /* 静态方法 */, jlong handle, jbyteArray data,
        jint byteOffset, jint byteCount, jint frameBytes )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || frameBytes <= 0 )
    {
        return( -1 );
    }
    if( s->disconnected.load( std::memory_order_acquire ))
    {
        return( -1 );
    }
    if( byteCount <= 0 || data == nullptr )
    {
        return( 0 );
    }
    const int32_t frames = byteCount / frameBytes;
    if( frames <= 0 )
    {
        return( 0 );
    }

    jbyte* raw = env->GetByteArrayElements( data, nullptr );
    if( raw == nullptr )
    {
        return( -2 );
    }
    const size_t written = s->ring.write(
        reinterpret_cast< const uint8_t* >( raw ) + byteOffset, static_cast< size_t >( frames ));
    env->ReleaseByteArrayElements( data, raw, JNI_ABORT );

    if( written > 0 )
    {
        s->framesWritten.fetch_add( static_cast< int64_t >( written ), std::memory_order_relaxed );
    }
    return( static_cast< jint >( written ));
}

/**
 * 设置软件增益（Q16，65536 = 1.0）。
 *
 * 只写 atomic，`onAudioReady` 每次回调读一次 —— 所以换音量在下一个回调周期生效，
 * 不受环形缓冲里已提交数据的影响（在写侧乘的话要等 250 ms 才听得到）。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeSetGain(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle, jint gainQ16 )
{
    if( OboeStream* s = asStream( handle ); s != nullptr )
    {
        const int32_t clamped = gainQ16 < 0 ? 0
                                : ( gainQ16 > annie::kGainUnity ? annie::kGainUnity : gainQ16 );
        s->gainQ16.store( clamped, std::memory_order_release );
    }
}

/** 暂停 = 回调改出静音、**不从环形缓冲取数据**（位置随之冻结）；流本身保持 STARTED */
extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativePause(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s != nullptr )
    {
        s->paused.store( true, std::memory_order_release );
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeResume(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr )
    {
        return;
    }
    s->paused.store( false, std::memory_order_release );
    if( s->stream )
    {
        // 已经从 STARTING/STARTED 再 requestStart 会返回 -895(INVALID_STATE)，
        // 无害但会污染日志（实测每首歌两条）。这里先看状态。
        const oboe::StreamState state = s->stream->getState();
        if( state != oboe::StreamState::Started && state != oboe::StreamState::Starting )
        {
            s->stream->requestStart();
        }
    }
}

/**
 * 实际用的后端：1 = AAudio，2 = OpenSL ES，0 = 拿不到。
 *
 * 必须能读出来：独占成不成立完全取决于它 —— OpenSL 没有 EXCLUSIVE 概念，
 * 拿到 SHARED 是必然结果而不是设备拒绝。以前这条信息只在代码注释里，
 * 出问题时只能干瞪眼。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeAudioApi(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( 0 );
    }
    return( s->stream->getAudioApi() == oboe::AudioApi::AAudio ? 1 : 2 );
}

namespace
{

class SilentCallback : public oboe::AudioStreamDataCallback
{
public:
    oboe::DataCallbackResult onAudioReady( oboe::AudioStream* /* stream */, void* /* audioData */,
                                           int32_t /* numFrames */ ) override
    {
        return( oboe::DataCallbackResult::Continue );
    }
};

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeProbeExclusive(
        JNIEnv* env, jclass /* 静态方法 */, jint sampleRate, jint pcmFormat )
{
    if( sampleRate <= 0 )
    {
        return( env->NewStringUTF( "err:rate" ));
    }

    oboe::AudioFormat format = oboe::AudioFormat::Float;
    int bits = 32;
    if( pcmFormat == kFormatI16 )
    {
        format = oboe::AudioFormat::I16;
        bits = 16;
    }
    else if( pcmFormat == kFormatI24Packed )
    {
        format = oboe::AudioFormat::I24;
        bits = 24;
    }

    int32_t capacityFrames = sampleRate / 10;
    if( capacityFrames < 512 )
    {
        capacityFrames = 512;
    }

    SilentCallback callback;
    oboe::AudioStreamBuilder builder;
    if( android_get_device_api_level() >= 26 )
    {
        builder.setAudioApi( oboe::AudioApi::AAudio );
    }
    builder.setDirection( oboe::Direction::Output )
        ->setSampleRate( sampleRate )
        ->setChannelCount( 2 )
        ->setFormat( format )
        ->setSharingMode( oboe::SharingMode::Exclusive )
        ->setPerformanceMode( oboe::PerformanceMode::LowLatency )
        ->setBufferCapacityInFrames( capacityFrames )
        ->setFramesPerDataCallback( 0 )
        ->setFormatConversionAllowed( false )
        ->setChannelConversionAllowed( false )
        ->setSampleRateConversionQuality( oboe::SampleRateConversionQuality::None )
        ->setDataCallback( &callback );

    std::shared_ptr< oboe::AudioStream > stream;
    const oboe::Result opened = builder.openStream( stream );
    if( opened != oboe::Result::OK || !stream )
    {
        char buf[ 32 ];
        snprintf( buf, sizeof( buf ), "err:%d", static_cast< int >( opened ));
        return( env->NewStringUTF( buf ));
    }

    const bool onAaudio = stream->getAudioApi() == oboe::AudioApi::AAudio;
    const bool exclusive = stream->getSharingMode() == oboe::SharingMode::Exclusive;
    const int32_t realRate = stream->getSampleRate();
    const oboe::AudioFormat realFormat = stream->getFormat();
    const oboe::AudioFormat hardwareFormat = stream->getHardwareFormat();
    stream->close();
    stream.reset();

    if( !onAaudio )
    {
        return( env->NewStringUTF( "err:opensl" ));
    }

    int gotBits = bits;
    if( realFormat == oboe::AudioFormat::I16 )
    {
        gotBits = 16;
    }
    else if( realFormat == oboe::AudioFormat::I24 )
    {
        gotBits = 24;
    }
    else if( realFormat == oboe::AudioFormat::I32 || realFormat == oboe::AudioFormat::Float )
    {
        gotBits = 32;
    }

    int hardware = 0;
    if( hardwareFormat == oboe::AudioFormat::I16 )
    {
        hardware = kFormatI16;
    }
    else if( hardwareFormat == oboe::AudioFormat::Float )
    {
        hardware = kFormatFloat;
    }
    else if( hardwareFormat == oboe::AudioFormat::I24 )
    {
        hardware = kFormatI24Packed;
    }

    const bool formatKnown = realFormat == oboe::AudioFormat::I16 ||
                             realFormat == oboe::AudioFormat::I24 ||
                             realFormat == oboe::AudioFormat::I32 ||
                             realFormat == oboe::AudioFormat::Float;
    int unknown = 0;
    if( realRate <= 0 )
    {
        unknown |= kUnknownRate;
    }
    if( !formatKnown )
    {
        unknown |= kUnknownFormat;
    }
    if( hardware == 0 )
    {
        unknown |= kUnknownHardwareFormat;
    }

    char buf[ 112 ];
    snprintf( buf, sizeof( buf ), "%s/%d/%d/%s/hw=%d/unk=%d",
             exclusive ? "EXCLUSIVE" : "SHARED",
             static_cast< int >( realRate ),
             gotBits,
             "LOW_LATENCY",
             hardware,
             unknown );
    return( env->NewStringUTF( buf ));
}

extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeFlush(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr )
    {
        return;
    }
    s->ring.clear();
    s->framesRead.store( 0, std::memory_order_relaxed );
    s->framesWritten.store( 0, std::memory_order_relaxed );
}

extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeClose(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr )
    {
        return;
    }
    if( s->stream )
    {
        s->stream->requestStop();
        s->stream->close();
        s->stream.reset();
    }
    delete s;
}

/** 硬件时钟：`out[0]` = 已播到的帧位置，`out[1]` = 对应 nanoTime；false = 拿不到 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeTimestamp(
        JNIEnv* env, jclass /* 静态方法 */, jlong handle, jlongArray out )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream || out == nullptr )
    {
        return( JNI_FALSE );
    }
    int64_t framePosition = 0;
    int64_t timeNanos = 0;
    const oboe::Result r = s->stream->getTimestamp( CLOCK_MONOTONIC, &framePosition, &timeNanos );
    if( r != oboe::Result::OK )
    {
        return( JNI_FALSE );
    }
    const jlong values[ 2 ] = { static_cast< jlong >( framePosition ), static_cast< jlong >( timeNanos ) };
    env->SetLongArrayRegion( out, 0, 2, values );
    return( JNI_TRUE );
}

/** 已写进我方的帧数（不含被丢弃的部分）；-1 = 句柄无效 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeFramesWritten(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr )
    {
        return( -1 );
    }
    return( static_cast< jlong >( s->framesWritten.load( std::memory_order_relaxed )));
}

/** 设备真正取走的帧数（静音填充不算）—— 位置模型的主要来源之一 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeFramesRead(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr )
    {
        return( 0 );
    }
    return( static_cast< jlong >( s->framesRead.load( std::memory_order_relaxed )));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeSharingMode(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( -1 );
    }
    return( s->stream->getSharingMode() == oboe::SharingMode::Exclusive ? 1 : 0 );
}

/** 流的**真实**采样率；0 = 拿不到。与请求值不等 = 中间被重采样了 */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeSampleRate(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( 0 );
    }
    return( s->stream->getSampleRate() );
}

/** 流的**真实**格式（`aaudio_format_t` 口径）；0 = 拿不到 */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeFormat(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( 0 );
    }
    switch( s->stream->getFormat())
    {
        case oboe::AudioFormat::I16:
            return( kFormatI16 );
        case oboe::AudioFormat::Float:
            return( kFormatFloat );
        case oboe::AudioFormat::I24:
            return( kFormatI24Packed );
        default:
            return( 0 );
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeHardwareFormat(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( 0 );
    }
    switch( s->stream->getHardwareFormat())
    {
        case oboe::AudioFormat::I16:
            return( kFormatI16 );
        case oboe::AudioFormat::Float:
            return( kFormatFloat );
        case oboe::AudioFormat::I24:
            return( kFormatI24Packed );
        default:
            return( 0 );
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeHardwareSampleRate(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( 0 );
    }
    const int32_t rate = s->stream->getHardwareSampleRate();
    return( rate == oboe::kUnspecified ? 0 : rate );
}

/** 缓冲容量（帧）；0 = 拿不到。远大于请求值 = HAL 不接受小缓冲 */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeBufferCapacity(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( 0 );
    }
    return( s->stream->getBufferCapacityInFrames() );
}

/** 当前生效的缓冲深度（帧）；0 = 拿不到 */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeBufferSize(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( 0 );
    }
    return( s->stream->getBufferSizeInFrames() );
}

/** 流状态：枚举值与 `aaudio_stream_state_t` 一致（1 OPEN … 12 DISCONNECTED） */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeStreamState(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( 0 );
    }
    return( static_cast< jint >( s->stream->getState()));
}

/** 欠载次数（静音填充的调用数）—— 只给"输出能力（实测）"面板用，不参与任何判定 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeUnderrunCount(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr )
    {
        return( 0 );
    }
    return( static_cast< jlong >( s->underruns.load( std::memory_order_relaxed )));
}
