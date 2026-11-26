#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <elf.h>
#include <pthread.h>
#include <signal.h>
#include <setjmp.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

/**
 * DAP 内置 DAC 直通的 native 侧：**自己开一条 `AudioTrack`**。
 *
 * ══ 为什么不能用 Java 的 AudioTrack ══
 *
 * `AudioTrack` 的构造链会用 `audio_bytes_per_sample(format)` 反推每帧字节数。
 * 私有 `audio_format_t`（DSD 段）不在 framework 的公开枚举里 → 得到 0 →
 * `bufferSize / 0`，那是 native 里的 SIGFPE，Java 的 try/catch 接不住。
 * 我们自己给 frameCount，就绕开了那次推导。
 *
 * ══ 为什么不能用 AAudio ══
 *
 * `AAudioStreamBuilder_setFormat` 只接受 `aaudio_format_t` 的四个合法值
 * （I16 / FLOAT / I24_PACKED / I32），别的直接 `AAUDIO_ERROR_ILLEGAL_ARGUMENT`。
 *
 * ══ 为什么不需要厂商私有库 ══
 *
 * `libaudioclient.so` 是 AOSP 系统库（每台 Android 都有），`AudioTrack` 的 C++ 符号
 * 必须保持导出 —— libmedia 的 JNI 自己要用。厂商差异只体现在**传给 `set()` 的
 * format 数字**上，那个值由 Kotlin 侧的候选表给出，本文件一个都不硬编码。
 *
 * ══ 为什么不用 dlsym 精确匹配 mangled 名 ══
 *
 * `AudioTrack::set` 的名字带全部参数类型，跨版本会变（12 起多了
 * `AttributionSourceState`）。硬编码一处名字等于只支持一个版本。
 * 这里改成：`dl_iterate_phdr` 找到 `libaudioclient.so` → 解析 `.dynsym` →
 * 按 `7android10AudioTrack` 收集 → 看 `set` 的名字里有没有 `AttributionSourceState`
 * 判 ABI，再选对应原型。
 *
 * ⚠️ const 成员函数（`getPosition`）的 mangled 前缀是 `_ZNK`，所以匹配一律用
 * `_ZN` 三个字符 + `strstr`，不能写死 `_ZN7android`。
 *
 * ══ 支持范围（诚实标注）══
 *
 * | ABI | 判据（符号名里含） | 状态 |
 * |---|---|---|
 * | Android 13–16 | `wp<IAudioTrackCallback>` + `audio_attributes_t*` | **已打通**（实测 OnePlus Android 16、飞傲 JM21 Android 13） |
 * | Android 12 | `AttributionSourceState`，无 callback 参数 | 未实现（形状不同） |
 * | Android 10–11 | `AttributionSource`（不含 State） | 未实现 |
 * | Android 7–9 | 两者都没有（uid/pid 直接传参） | 未实现，返回 `kErrAbiUnsupported` |
 *
 * ⚠️ 同一个库里 `AudioTrack::set` 往往有**三份重载**（回调函数指针版 / `wp` 版 /
 * 老式 `j` format 版）。所以"匹配到名字里带 `set` 就 break"是错的，必须在命中之后才 break
 * —— 否则 abi 留 0、整条路被判不支持（JM21 上真踩过）。
 *
 * ══ 一次错判的记录：CFI ══
 *
 * 曾经断定"Android 16 开了 LLVM CFI，间接调用会被 `__cfi_slowpath` 拦下 → abort"，
 * 依据是第一次试就 SIGABRT。**那个结论是错的**：CFI 确实在这份 ROM 上，但不是拦路的原因。
 * 真实原因是五个参数错误（`AttributionSourceState` 缓冲不够、栈上对象被写坏、
 * Java/native 两套编号混用、`streamType` 传 3），修完就通了。
 *
 * 教训：abort 只说明"某处不变量被破坏"，不说明是哪一处。当时该对着 logcat 里
 * Media3 成功那条的字段逐项比对，而不是从一个看起来最像的机制倒推。
 *
 * ══ 失败一律返回错误码，不崩 ══
 *
 * `attrCtor` / `ctor` / `set` 三段都在 `sigaction + sigsetjmp` 保护里跑：厂商 HAL 收到
 * 不认识的 format 时，崩在 `set()` 里是常态（不是异常）。捕获后把信号代号写进日志，
 * Kotlin 侧据此换下一个候选。
 */

#define LOG_TAG "annie-dap"
#define LOGI( ... ) __android_log_print( ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__ )
#define LOGW( ... ) __android_log_print( ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__ )

namespace
{

// ── 返回码（Kotlin 侧 `DapNativeResult` 一一对应）──
constexpr int32_t kOk = 0;
constexpr int32_t kErrNoLib = -1;          // 找不到 libaudioclient.so
constexpr int32_t kErrNoSymbol = -2;       // 符号不全
constexpr int32_t kErrAbiUnsupported = -3; // 老 ABI（7–9）
constexpr int32_t kErrSetRejected = -4;    // set() 返回非 0
constexpr int32_t kErrSetCrashed = -5;     // 建流过程中收到致命信号
constexpr int32_t kErrNotOpen = -6;
constexpr int32_t kErrBufferNotDirect = -7;
/** 构造函数没有真正初始化对象（vtable 或 mStatus 不符） */
constexpr int32_t kErrCtorFailed = -8;

/** `audio_attributes_t`：POD，32 字节 */
struct AudioAttributesCompat
{
    uint32_t contentType;
    uint32_t usage;
    uint32_t source;
    uint32_t flags;
    char tags[ 16 ];
};

static_assert( sizeof( AudioAttributesCompat ) == 32, "audio_attributes_t 布局变化" );

/**
 * `AttributionSourceState` 的**真实大小**由反汇编推断（Android 16 的 `libaudioclient.so`）：
 *
 * ```
 * +0x00 int32 pid        +0x04 int32 uid
 * +0x08 8 bytes          +0x10 4 bytes
 * +0x18 optional<string> #1（engaged @ +0x30）
 * +0x38 optional<string> #2（engaged @ +0x50）
 * +0x58 sp<...>          +0x60 mutex
 * +0x80 vector<...>（begin/end/cap 三个指针）
 * ```
 *
 * 即**至少 0x98 = 152 字节**。曾经只给 128 —— 构造里的拷贝会写到 +0x98 之外。
 * 这类越界不会当场报错，而是后续某次 malloc/free 检测到堆元数据坏了再 `abort()`，
 * 报出来的名字往往指向不相干的地方（实测报的是 `__stack_chk_fail`）。
 * 给到 512 是留足后续版本加字段的空间；零填充对 libc++ 的 `optional` / `vector` /
 * `sp` 都是合法的"空"状态（拷贝里全都是 `cbz` / `b.eq` 跳过）。
 */
constexpr size_t kAttributionSourceBytes = 512;

/** 对象本体布局不透明，给足空间（实际 sizeof 约 700） */
constexpr size_t kTrackObjectBytes = 0x800;

// ── ELF 符号扫描 ──

struct SymbolIndex
{
    const ElfW( Sym )* symtab = nullptr;
    const char* strtab = nullptr;
    uint32_t count = 0;
    uintptr_t base = 0;
    bool ready = false;
};

SymbolIndex g_index;

/** GNU_HASH 里算符号总数（没有 DT_HASH 的 .so 只有它可用） */
static uint32_t gnuHashCount( const uint32_t* hash )
{
    const uint32_t nbuckets = hash[ 0 ];
    const uint32_t symoffset = hash[ 1 ];
    const uint32_t bloomSize = hash[ 2 ];
    // bloom filter 每项是 ElfW(Addr)（64 位 = 8 字节 = 2 个 uint32）
    const uint32_t* buckets = hash + 4 + bloomSize * ( sizeof( ElfW( Addr )) / sizeof( uint32_t ));

    uint32_t maxSym = 0;
    for( uint32_t i = 0; i < nbuckets; ++i )
    {
        if( buckets[ i ] > maxSym )
        {
            maxSym = buckets[ i ];
        }
    }
    if( maxSym < symoffset )
    {
        return( symoffset );
    }

    const uint32_t* chain = buckets + nbuckets;
    uint32_t idx = maxSym;
    while(( chain[ idx - symoffset ] & 1u ) == 0 )
    {
        ++idx;
        if( idx > ( 1u << 24 ))
        {
            return( 0 ); // 坏表：宁可判"没有符号"也不死循环
        }
    }
    return( idx + 1 );
}

static int findLibAudioClient( struct dl_phdr_info* info, size_t /*size*/, void* /*data*/ )
{
    if( info->dlpi_name == nullptr )
    {
        return( 0 );
    }
    const char* slash = strrchr( info->dlpi_name, '/' );
    const char* name = slash ? slash + 1 : info->dlpi_name;
    if( strcmp( name, "libaudioclient.so" ) != 0 )
    {
        return( 0 );
    }

    const ElfW( Dyn )* dyn = nullptr;
    for( int i = 0; i < info->dlpi_phnum; ++i )
    {
        if( info->dlpi_phdr[ i ].p_type == PT_DYNAMIC )
        {
            dyn = reinterpret_cast< const ElfW( Dyn )* >(
                info->dlpi_addr + info->dlpi_phdr[ i ].p_vaddr );
            break;
        }
    }
    if( dyn == nullptr )
    {
        return( 0 );
    }

    uintptr_t symtab = 0, strtab = 0, gnuHash = 0, sysvHash = 0;
    size_t strsz = 0;
    for( const ElfW( Dyn )* d = dyn; d->d_tag != DT_NULL; ++d )
    {
        switch( d->d_tag )
        {
            case DT_SYMTAB: symtab = d->d_un.d_ptr; break;
            case DT_STRTAB: strtab = d->d_un.d_ptr; break;
            case DT_STRSZ: strsz = d->d_un.d_val; break;
            case DT_GNU_HASH: gnuHash = d->d_un.d_ptr; break;
            case DT_HASH: sysvHash = d->d_un.d_ptr; break;
            default: break;
        }
    }
    if( symtab == 0 || strtab == 0 || strsz == 0 )
    {
        return( 0 );
    }

    // DT_* 里是链接期虚拟地址；.so 是 PIE，要加加载基址
    const uintptr_t bias = info->dlpi_addr;
    uint32_t count = 0;
    if( sysvHash != 0 )
    {
        count = reinterpret_cast< const uint32_t* >( sysvHash + bias )[ 1 ]; // nchain
    }
    else if( gnuHash != 0 )
    {
        count = gnuHashCount( reinterpret_cast< const uint32_t* >( gnuHash + bias ));
    }
    if( count == 0 )
    {
        return( 0 );
    }

    g_index.symtab = reinterpret_cast< const ElfW( Sym )* >( symtab + bias );
    g_index.strtab = reinterpret_cast< const char* >( strtab + bias );
    g_index.count = count;
    g_index.base = bias;
    g_index.ready = true;
    LOGI( "libaudioclient base=%p syms=%u", reinterpret_cast< void* >( bias ), count );
    return( 1 ); // 找到即停
}

static bool ensureIndex()
{
    if( g_index.ready )
    {
        return( true );
    }
    dl_iterate_phdr( findLibAudioClient, nullptr );
    return( g_index.ready );
}

/**
 * 在符号表里找：mangled 名以 `_ZN` 开头（含 `_ZNK`）、含 [needle]、且包含全部 [must]。
 *
 * `_ZNK` 也要覆盖 —— `getPosition` 是 const 成员函数，它的前缀是 `_ZNK`，
 * 只匹配 `_ZN7android` 会漏掉它（而这个符号缺失会让位置永远为 0）。
 */
static void* findMangled( const char* needle, const char* const* must, int mustCount )
{
    if( !ensureIndex())
    {
        return( nullptr );
    }
    for( uint32_t i = 1; i < g_index.count; ++i )
    {
        const ElfW( Sym )& s = g_index.symtab[ i ];
        if( s.st_name == 0 || s.st_shndx == SHN_UNDEF || s.st_value == 0 )
        {
            continue;
        }
        const char* name = g_index.strtab + s.st_name;
        if( strncmp( name, "_ZN", 3 ) != 0 )
        {
            continue;
        }
        if( strstr( name, needle ) == nullptr )
        {
            continue;
        }
        bool all = true;
        for( int k = 0; k < mustCount; ++k )
        {
            if( strstr( name, must[ k ] ) == nullptr )
            {
                all = false;
                break;
            }
        }
        if( !all )
        {
            continue;
        }
        return( reinterpret_cast< void* >( s.st_value + g_index.base ));
    }
    return( nullptr );
}

static void* findTrack( const char* must0 = nullptr, const char* must1 = nullptr )
{
    const char* must[ 2 ];
    int n = 0;
    if( must0 != nullptr )
    {
        must[ n++ ] = must0;
    }
    if( must1 != nullptr )
    {
        must[ n++ ] = must1;
    }
    return( findMangled( "7android10AudioTrack", must, n ));
}

// ── ABI 原型 ──
//
// 两条 ABI 的差别只有第 13 个参数：`AttributionSourceState`（12+）vs
// `AttributionSource`（10–11）。两者都带 pid/uid，构造方式一样。

using CtorFn = void ( * )( void* self );
using DtorFn = void ( * )( void* self );

/** `AudioTrack(const content::AttributionSourceState&)` —— 唯一只收一个参数的构造 */
using CtorAttrSrcFn = void ( * )( void* self, const void* attributionSource );

/**
 * `set(...)` 的**真实**形状由该 ROM 的 mangled 名决定，不是由 AOSP 记忆决定。
 *
 * 实测（OnePlus PLQ110 / Android 16，`_ZN7android10AudioTrack3setE…`）：
 *
 * ```
 * set( 19audio_stream_type_t, j sampleRate, 14audio_format_t, 20audio_channel_mask_t,
 *      m frameCount, 20audio_output_flags_t,
 *      RK wp<IAudioTrackCallback>, i notificationFrames,
 *      RK sp<IMemory>, b threadCanCallJava, 15audio_session_t,
 *      transfer_type, PK audio_offload_info_t,
 *      RK content::AttributionSourceState, PK audio_attributes_t, b, f, i )
 * ```
 *
 * 三处与老记忆相反，任何一处照记忆写都是"参数整体错位"（静音或崩）：
 * 1. `wp<IAudioTrackCallback>` 在 `notificationFrames` **之前**；
 * 2. `sp<IMemory>` 在 `notificationFrames` **之后**，且 `threadCanCallJava` 紧跟其后；
 * 3. `audio_attributes_t` 是**指针**且排在 `AttributionSourceState` **之后**，末尾不是 uid/pid。
 *
 * `RK...sp/wp...` 是引用 ⇒ 必须传**对象地址**，不能传 `nullptr`（`set()` 内部会
 * `promote()` / 判空，空指针进去就是解引用空指针崩在 HAL 里）。
 */
using SetFn = int32_t ( * )(
    void* self, int streamType, uint32_t sampleRate, int format, uint32_t channelMask,
    size_t frameCount, int flags,
    const void* callback, int32_t notificationFrames,
    const void* sharedBuffer, bool threadCanCallJava, int32_t sessionId,
    int32_t transferType, const void* offloadInfo,
    const void* attributionSource, const AudioAttributesCompat* attributes,
    bool doNotReconnect, float maxRequiredSpeed, int32_t reserved );

/** libc++ 的 `sp<T>` / `wp<T>` 内部就是两个指针；零填充 = 合法的空智能指针 */
struct SmartPtrStub
{
    void* mPtr;
    void* mRefs;
};

using StartFn = int32_t ( * )( void* self );
using StopFn = void ( * )( void* self );
using PauseFn = void ( * )( void* self );
using FlushFn = void ( * )( void* self );
using WriteFn = int64_t ( * )( void* self, const void* buffer, size_t size, bool blocking );
using GetPositionFn = int32_t ( * )( const void* self, uint32_t* position );
using SetVolumeFn = int32_t ( * )( void* self, float left, float right );
/**
 * `uint32_t AudioTrack::getSampleRate() const`。
 *
 * 这是**唯一**能回答"这条轨到底跑在什么率上"的接口：`set()` 返回 0 只说明参数被收下，
 * 而 HAL 完全可以把率吞掉（换线程、按自己的档位重开）。参考实现就是用它当建流判据
 * （`rate == 请求值 || rate == 0` 才算成立），我们照抄这一条。
 */
using GetSampleRateFn = uint32_t ( * )( const void* self );

struct Api
{
    CtorAttrSrcFn ctorAttrSrc = nullptr;
    DtorFn dtor = nullptr;
    SetFn set = nullptr;
    StartFn start = nullptr;
    StopFn stop = nullptr;
    PauseFn pause = nullptr;
    FlushFn flush = nullptr;
    WriteFn write = nullptr;
    GetPositionFn getPosition = nullptr;
    SetVolumeFn setVolume = nullptr;
    GetSampleRateFn getSampleRate = nullptr;
    /**
     * `set()` 的布局代号，只用于日志与诊断：
     * 3 = Android 14+（`wp<IAudioTrackCallback>` + `audio_attributes_t*`），本文件只实现这一种。
     */
    int32_t abi = 0;
};

Api g_api;
int32_t g_status = kErrNoSymbol;
bool g_probed = false;

static void resolveApi()
{
    if( g_probed )
    {
        return;
    }
    g_probed = true;

    if( !ensureIndex())
    {
        g_status = kErrNoLib;
        LOGW( "找不到 libaudioclient.so" );
        return;
    }

    // 构造：只认 `AudioTrack(const content::AttributionSourceState&)`。
    // 实测该 ROM 上**没有**无参构造（`C1Ev`），也没有 `AttributionSourceState` 的导出构造
    // —— 后者是 inline，所以 AttributionSourceState 只能零填充（见 nativeOpen）。
    g_api.ctorAttrSrc = reinterpret_cast< CtorAttrSrcFn >(
        findTrack( "C1ERKNS_7content22AttributionSourceStateE" ));
    if( g_api.ctorAttrSrc == nullptr )
    {
        g_api.ctorAttrSrc = reinterpret_cast< CtorAttrSrcFn >(
            findTrack( "C2ERKNS_7content22AttributionSourceStateE" ));
    }
    g_api.dtor = reinterpret_cast< DtorFn >( findTrack( "D1Ev" ));
    g_api.start = reinterpret_cast< StartFn >( findTrack( "5startE" ));
    g_api.stop = reinterpret_cast< StopFn >( findTrack( "4stopE" ));
    g_api.pause = reinterpret_cast< PauseFn >( findTrack( "5pauseE" ));
    g_api.flush = reinterpret_cast< FlushFn >( findTrack( "5flushE" ));
    g_api.write = reinterpret_cast< WriteFn >( findTrack( "5writeEPKv" ));
    g_api.getPosition = reinterpret_cast< GetPositionFn >( findTrack( "11getPositionEPj" ));
    // 缺失不算致命：没有它就退化成"只信 set() 的返回值"（旧行为），不要因此拒开流。
    g_api.getSampleRate = reinterpret_cast< GetSampleRateFn >( findTrack( "13getSampleRateEv" ));
    // `setVolumeEff`（左右分开）优先，退回 `setVolumeEf`（单值，左右同值，语义等价）。
    // 两个符号都在时不能靠 dynsym 顺序 —— 那是不保证的。
    g_api.setVolume = reinterpret_cast< SetVolumeFn >( findTrack( "9setVolumeEff" ));
    if( g_api.setVolume == nullptr )
    {
        g_api.setVolume = reinterpret_cast< SetVolumeFn >( findTrack( "9setVolumeEf" ));
    }

    // `set`：只接受带 `wp<IAudioTrackCallback>` 的那一版（Android 14+ / AOSP 13 的布局）。
    //
    // ⚠️ 同一个库里 `AudioTrack::set` 常有**三份重载**（回调函数指针版、`wp` 版、
    // 老式 `j` format 版）。所以匹配到第一个 `set` 就 `break` 是错的 —— 那份可能不是我们要的，
    // 结果 `abi` 留 0，整条路被判"不支持"。必须在**命中之后**才 break。
    {
        for( uint32_t i = 1; i < g_index.count; ++i )
        {
            const ElfW( Sym )& s = g_index.symtab[ i ];
            if( s.st_name == 0 || s.st_shndx == SHN_UNDEF || s.st_value == 0 )
            {
                continue;
            }
            const char* name = g_index.strtab + s.st_name;
            if( strncmp( name, "_ZN", 3 ) != 0 )
            {
                continue;
            }
            if( strstr( name, "10AudioTrack3setE" ) == nullptr )
            {
                continue;
            }
            if( strstr( name, "IAudioTrackCallback" ) != nullptr &&
                strstr( name, "22AttributionSourceState" ) != nullptr &&
                strstr( name, "18audio_attributes_t" ) != nullptr )
            {
                g_api.set = reinterpret_cast< SetFn >(
                    reinterpret_cast< void* >( s.st_value + g_index.base ));
                g_api.abi = 3;
                break;
            }
        }
    }

    if( g_api.ctorAttrSrc == nullptr || g_api.dtor == nullptr || g_api.write == nullptr ||
        g_api.getPosition == nullptr || g_api.start == nullptr || g_api.pause == nullptr ||
        g_api.flush == nullptr )
    {
        g_status = kErrNoSymbol;
        LOGW( "AudioTrack 符号不全 ctor=%p write=%p pos=%p start=%p",
              reinterpret_cast< void* >( g_api.ctorAttrSrc ),
              reinterpret_cast< void* >( g_api.write ),
              reinterpret_cast< void* >( g_api.getPosition ),
              reinterpret_cast< void* >( g_api.start ));
        return;
    }
    if( g_api.set == nullptr )
    {
        g_status = kErrAbiUnsupported;
        LOGW( "AudioTrack::set 不是 Android 14+ 布局（abi=%d）", g_api.abi );
        return;
    }

    g_status = kOk;
    LOGI( "AudioTrack abi=%d ctorAttr=%p set=%p", g_api.abi,
          reinterpret_cast< void* >( g_api.ctorAttrSrc ),
          reinterpret_cast< void* >( g_api.set ));
}

// ── 崩溃保护 ──
//
// **每一个** JNI 入口都要保护，不只是建流那一段。`set()` 返回 0 不等于对象真的可用：
// 半初始化的 `AudioTrack` 会在第一次 `write`/`start` 时崩，同样杀进程。
//
// ══ 为什么"装一次 + 转发"而不是每次替换 ══
//
// ART 自己装了 SIGSEGV handler（它用这个实现隐式 null 检查）。早期的写法在
// `leaveGuard()` 里恢复成 `SIG_DFL` —— 那等于**永久破坏** App 的 null 检查语义：
// 之后任何一次空引用都直接杀进程，连 JVM 的 crash logger 都不触发（实测就是这样闪退的）。
//
// 现在改成：首次使用时 `pthread_once` 安装我们的 handler，同时把 ART 的那个存下来；
// 保护区外收到信号就**转交给它**。我们的 handler 常驻，但只在 `g_inGuard` 时改变行为。
//
// 每线程一份 jmp 缓冲 + 标志：`siglongjmp` 跨线程是未定义行为，而 `write` 跑在
// Media3 的渲染线程上。

static __thread sigjmp_buf g_jmp;
static __thread volatile sig_atomic_t g_faultSignal = 0;
static __thread volatile sig_atomic_t g_inGuard = 0;

/**
 * 崩溃现场（信号处理器里只做 signal-safe 的赋值）。
 *
 * 为什么一定要它：`abort()` 的调用者可能是 `__stack_chk_fail`、`__cfi_slowpath`、
 * `__libcpp_verbose_abort` 或 libc 的 `LOG_ALWAYS_FATAL` —— 这四种的修法完全不同。
 * 光看"abort 了"分不出来，而 ROM 上 logcat 又常被丢。记录 PC/LR + `dladdr` 反查模块名，
 * 一次就能定位到底是谁调的。
 */
static __thread uintptr_t g_faultPc = 0;
static __thread uintptr_t g_faultLr = 0;

/**
 * 最近一次 `set()` 的 `status_t` 返回值。
 *
 * 必须带出来：ROM 上 logcat 常被丢，而 `status_t` 的具体值正是判断"哪一项参数被拒"
 * 的唯一线索 —— `BAD_VALUE(-22)` 是参数非法，`INVALID_OPERATION(-38)` 是这台设备的
 * HAL 没有该端口，`PERMISSION_DENIED(-1)` 是 attribution 有问题。
 */
static int32_t g_lastSetResult = 0;

/** `start()` 的返回值。`stop()` 会不会挂与它强相关，所以必须单独记 */
static int32_t g_lastStartResult = 0;

/** `getPosition()` 的返回值。非 0 时播放头恒为 0，看起来像"没在播"，其实是在报错 */
static int32_t g_lastPositionResult = 0;

/** 把地址符号化成 `名字+偏移@库路径`（只在 JNI 侧调用，不在信号处理器里） */
static void symbolize( uintptr_t addr, char* out, size_t cap )
{
    if( addr == 0 )
    {
        snprintf( out, cap, "-" );
        return;
    }
    Dl_info info;
    if( dladdr( reinterpret_cast< void* >( addr ), &info ) == 0 )
    {
        snprintf( out, cap, "0x%lx", static_cast< unsigned long >( addr ));
        return;
    }
    snprintf( out, cap, "%s+0x%lx@%s",
              info.dli_sname != nullptr ? info.dli_sname : "?",
              static_cast< unsigned long >( addr - reinterpret_cast< uintptr_t >( info.dli_saddr )),
              info.dli_fname != nullptr ? info.dli_fname : "?" );
}

static struct sigaction g_oldSegv;
static struct sigaction g_oldBus;
static struct sigaction g_oldIll;
static struct sigaction g_oldAbrt;
static struct sigaction g_oldTrap;
static struct sigaction g_oldFpe;

static void forwardTo( const struct sigaction* old, int sig, siginfo_t* si, void* uc )
{
    if( old == nullptr )
    {
        signal( sig, SIG_DFL );
        raise( sig );
        return;
    }
    if(( old->sa_flags & SA_SIGINFO ) != 0 && old->sa_sigaction != nullptr )
    {
        old->sa_sigaction( sig, si, uc );
        return;
    }
    if( old->sa_handler == SIG_DFL || old->sa_handler == SIG_IGN )
    {
        signal( sig, old->sa_handler );
        raise( sig );
        return;
    }
    if( old->sa_handler != nullptr )
    {
        old->sa_handler( sig );
    }
}

static void faultHandler( int sig, siginfo_t* si, void* uc )
{
    if( g_inGuard )
    {
        g_faultSignal = sig;
        // 信号处理器里**只能**做这几件事：赋值 + siglongjmp。
        // 曾经在这里调 `dladdr` + `snprintf` 做符号化 —— 它们不是 signal-safe
        // （内部拿 loader 锁/malloc），结果比原始崩溃更糟：进程直接死，连日志都没留下。
        // 地址先记下来，符号化放到 JNI 侧的 `nativeDescribe()` 里做。
        if( uc != nullptr )
        {
            auto* ctx = static_cast< ucontext_t* >( uc );
            g_faultPc = static_cast< uintptr_t >( ctx->uc_mcontext.pc );
            g_faultLr = static_cast< uintptr_t >( ctx->uc_mcontext.regs[ 30 ] );
        }
        siglongjmp( g_jmp, 1 );
    }
    // 保护区外：原样交回 ART 的 handler（隐式 null 检查靠它）
    const struct sigaction* old;
    switch( sig )
    {
        case SIGSEGV: old = &g_oldSegv; break;
        case SIGBUS: old = &g_oldBus; break;
        case SIGILL: old = &g_oldIll; break;
        case SIGABRT: old = &g_oldAbrt; break;
        case SIGTRAP: old = &g_oldTrap; break;
        case SIGFPE: old = &g_oldFpe; break;
        default: old = nullptr; break;
    }
    forwardTo( old, sig, si, uc );
}

static void installHandlerOnce()
{
    struct sigaction sa;
    memset( &sa, 0, sizeof( sa ));
    sa.sa_sigaction = faultHandler;
    sigemptyset( &sa.sa_mask );
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigaction( SIGSEGV, &sa, &g_oldSegv );
    sigaction( SIGBUS, &sa, &g_oldBus );
    sigaction( SIGILL, &sa, &g_oldIll );
    // SIGABRT / SIGTRAP / SIGFPE 也要接：libc++ 的断言走 `__builtin_trap`（SIGILL/SIGTRAP），
    // 失败断言与堆检查走 `abort`（SIGABRT）。少接一个，那一条路就还是杀进程。
    sigaction( SIGABRT, &sa, &g_oldAbrt );
    sigaction( SIGTRAP, &sa, &g_oldTrap );
    sigaction( SIGFPE, &sa, &g_oldFpe );
}

static pthread_once_t g_handlerOnce = PTHREAD_ONCE_INIT;

static void ensureHandler()
{
    pthread_once( &g_handlerOnce, installHandlerOnce );
}

static int enterGuard()
{
    ensureHandler();
    g_faultSignal = 0;
    g_inGuard = 1;
    return( sigsetjmp( g_jmp, 1 ));
}

static void leaveGuard()
{
    g_inGuard = 0;
}

// ── 句柄 ──

struct Track
{
    void* self = nullptr;     // AudioTrack 对象
    void* attrSrc = nullptr;  // AttributionSourceState
    uint32_t wrapCount = 0;   // 32 位播放头回绕次数
    uint32_t positionLast = 0;
};

Track* asTrack( int64_t handle )
{
    return( reinterpret_cast< Track* >( static_cast< uintptr_t >( handle )));
}

} // namespace

// ── JNI ──

extern "C" JNIEXPORT jstring JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dap_NativeDapDac_nativeListSymbols(
        JNIEnv* env, jobject /*thiz*/, jstring needle, jint limit )
{
    // 诊断用：把 .dynsym 里含 [needle] 的名字列出来。ROM 的导出面只能实测，
    // 猜 mangled 名等于在赌（`C1Ev` 到底在不在，取决于该 ROM 的 visibility）。
    if( !ensureIndex())
    {
        return( env->NewStringUTF( "" ));
    }
    const char* n = env->GetStringUTFChars( needle, nullptr );
    if( n == nullptr )
    {
        return( env->NewStringUTF( "" ));
    }
    char buf[ 16384 ];
    size_t used = 0;
    int hits = 0;
    for( uint32_t i = 1; i < g_index.count && hits < static_cast< uint32_t >( limit ); ++i )
    {
        const ElfW( Sym )& s = g_index.symtab[ i ];
        if( s.st_name == 0 || s.st_shndx == SHN_UNDEF || s.st_value == 0 )
        {
            continue;
        }
        const char* name = g_index.strtab + s.st_name;
        if( strstr( name, n ) == nullptr )
        {
            continue;
        }
        const size_t len = strlen( name );
        if( used + len + 2 >= sizeof( buf ))
        {
            break;
        }
        memcpy( buf + used, name, len );
        used += len;
        buf[ used++ ] = '\n';
        buf[ used ] = '\0';
        ++hits;
    }
    env->ReleaseStringUTFChars( needle, n );
    return( env->NewStringUTF( buf ));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dap_NativeDapDac_nativeStatus(
        JNIEnv* /*env*/, jobject /*thiz*/ )
{
    resolveApi();
    return( g_status );
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dap_NativeDapDac_nativeDescribe(
        JNIEnv* env, jobject /*thiz*/ )
{
    resolveApi();
    char buf[ 512 ];
    // 符号化在 JNI 侧做（信号处理器里只能记地址）：`fault pc` 指向**谁真正 abort 的**，
    // `lr` 指向调用它的那一层。这两条能区分 `__stack_chk_fail` / `__cfi_slowpath` /
    // `__libcpp_verbose_abort` / libc `LOG_ALWAYS_FATAL` —— 四种的修法完全不同。
    char symPc[ 160 ] = "-";
    char symLr[ 160 ] = "-";
    symbolize( g_faultPc, symPc, sizeof( symPc ));
    symbolize( g_faultLr, symLr, sizeof( symLr ));
    snprintf( buf, sizeof( buf ),
              "status=%d abi=%d syms=%u setRc=%d fault=%d pc=%s lr=%s ctorAttr=%d set=%d write=%d pos=%d",
              g_status, g_api.abi, g_index.count, g_lastSetResult,
              static_cast< int >( g_faultSignal ), symPc, symLr,
              g_api.ctorAttrSrc != nullptr ? 1 : 0,
              g_api.set != nullptr ? 1 : 0, g_api.write != nullptr ? 1 : 0,
              g_api.getPosition != nullptr ? 1 : 0 );
    return( env->NewStringUTF( buf ));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dap_NativeDapDac_nativeAbi(
        JNIEnv* /*env*/, jobject /*thiz*/ )
{
    resolveApi();
    return( g_api.abi );
}

extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dap_NativeDapDac_nativeLastSetResult(
        JNIEnv* /*env*/, jobject /*thiz*/ )
{
    return( g_lastSetResult );
}

/**
 * 三段调用的返回值，一次带走：`set` / `start` / `getPosition`。
 *
 * 为什么合成一个字符串而不是三个 getter：这三者要一起看才有意义
 * （`start` 失败时 `getPosition` 恒 0 是必然的，不是"设备不出声"），
 * 而且结论要能原样写进探测文件，方便事后复盘。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dap_NativeDapDac_nativeStageResults(
        JNIEnv* env, jobject /*thiz*/ )
{
    char buf[ 128 ];
    snprintf( buf, sizeof( buf ), "set=%d start=%d pos=%d",
              g_lastSetResult, g_lastStartResult, g_lastPositionResult );
    return( env->NewStringUTF( buf ));
}

/**
 * 开流。返回句柄（>0）或负的错误码。
 *
 * @param format  `audio_format_t`：公开 PCM 值或候选表里的私有 DSD 值，由 Kotlin 决定
 * @param flags   `audio_output_flags_t`（`AUDIO_OUTPUT_FLAG_DIRECT` 等）
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dap_NativeDapDac_nativeOpen(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jint sampleRate, jint /*channelCount*/, jint channelMask,
        jint format, jint frameCount, jint flags )
{
    resolveApi();
    if( g_status != kOk )
    {
        return( g_status );
    }

    // 这三个是**全局**的最近一次返回值，进诊断串用。每次开流前清零，
    // 否则失败候选会把上一次（或上上一个）的数字带进自己的诊断里 —— 实测串味过。
    g_lastSetResult = 0;
    g_lastStartResult = 0;
    g_lastPositionResult = 0;

    auto* t = static_cast< Track* >( calloc( 1, sizeof( Track )));
    if( t == nullptr )
    {
        return( kErrNoSymbol );
    }
    t->self = calloc( 1, kTrackObjectBytes );
    t->attrSrc = calloc( 1, kAttributionSourceBytes );
    if( t->self == nullptr || t->attrSrc == nullptr )
    {
        free( t->self );
        free( t->attrSrc );
        free( t );
        return( kErrNoSymbol );
    }

    // 下面这三个"被 set() 收下的对象"一律放**堆**，不放栈：
    // 参数一旦错位，被调函数会往这些地址写，写到栈上就是金丝雀损坏 →
    // 返回时 `__stack_chk_fail` → abort（那条路无法用 siglongjmp 救回来）。
    auto* attrs = static_cast< AudioAttributesCompat* >( calloc( 1, sizeof( AudioAttributesCompat )));
    auto* emptyCallback = static_cast< SmartPtrStub* >( calloc( 1, sizeof( SmartPtrStub )));
    auto* emptySharedBuffer = static_cast< SmartPtrStub* >( calloc( 1, sizeof( SmartPtrStub )));
    if( attrs == nullptr || emptyCallback == nullptr || emptySharedBuffer == nullptr )
    {
        free( attrs );
        free( emptyCallback );
        free( emptySharedBuffer );
        free( t->self );
        free( t->attrSrc );
        free( t );
        return( kErrNoSymbol );
    }
    attrs->contentType = 2; // CONTENT_TYPE_MUSIC
    attrs->usage = 1;       // USAGE_MEDIA

    const int32_t uid = static_cast< int32_t >( getuid());
    const int32_t pid = static_cast< int32_t >( getpid());

    // AttributionSourceState：**零填充**即可，不调构造 —— 这个 ROM 上它的构造是 inline，
    // 没有导出符号。libc++ 下 `std::optional` / `std::vector` / `sp` 的全零状态都是合法的
    // "空"，而 `pid` / `uid` 是结构体最前两个 int32（AIDL parcelable 的稳定字段序）。
    *reinterpret_cast< int32_t* >( static_cast< uint8_t* >( t->attrSrc ) + 0 ) = pid;
    *reinterpret_cast< int32_t* >( static_cast< uint8_t* >( t->attrSrc ) + 4 ) = uid;

    // 构造 / set 都在崩溃保护里：厂商 HAL 收到不认识的 format 时崩在这里是常态
    if( enterGuard() != 0 )
    {
        leaveGuard();
        LOGW( "建流时收到信号 %d（format=0x%x rate=%d）—— 这组参数不成立",
              static_cast< int >( g_faultSignal ), format, sampleRate );
        // 同上：崩过的对象一律不释放
        free( attrs );
        free( emptyCallback );
        free( emptySharedBuffer );
        free( t->attrSrc );
        free( t );
        return( kErrSetCrashed );
    }

    g_api.ctorAttrSrc( t->self, t->attrSrc );

    // 构造函数返回 void，**无法**从返回值判断它是否真的初始化成功。
    //
    // 判据只有 vptr 非空：对象是 `calloc` 出来的，全零；ctor 只要跑过就必然写 vtable。
    //
    // 曾经还要求 `mStatus == NO_INIT(-19)`，那个偏移（+0x220）是**在一台 Android 16
    // 的 ROM 上读出来的**，换个版本就是垃圾值 —— 拿它当否决条件会把本来能用的设备判死
    // （实测 JM21 是 Android 13，布局不同）。它仍然记进日志/诊断，但不再参与判定。
    {
        const void* vptr = *reinterpret_cast< const void* const* >( t->self );
        const int32_t statusAfterCtor = *reinterpret_cast< const int32_t* >(
                static_cast< const uint8_t* >( t->self ) + 0x220 );
        if( vptr == nullptr )
        {
            leaveGuard();
            g_lastSetResult = statusAfterCtor;
            LOGW( "ctor 未完成：vptr=null mStatus(+0x220)=%d", statusAfterCtor );
            free( attrs );
            free( emptyCallback );
            free( emptySharedBuffer );
            free( t->attrSrc );
            free( t );
            return( kErrCtorFailed );
        }
        LOGI( "ctor ok vptr=%p mStatus(+0x220,仅供参考)=%d", vptr, statusAfterCtor );
    }

    const int32_t setResult = g_api.set(
            t->self,
            // streamType 传 **AUDIO_STREAM_DEFAULT(-1)**，不是 MUSIC(3)：
            // 有 attributes 时由 `usage` 决定路由，这也是 Media3 走 Java AudioTrack 时
            // 的实际取值（见 logcat 里成功那条 `streamType -1`）。传 3 会让部分 ROM 的
            // 定制 `set()` 走另一条校验分支并直接返回 BAD_VALUE。
            -1 /* AUDIO_STREAM_DEFAULT */, static_cast< uint32_t >( sampleRate ),
            format, static_cast< uint32_t >( channelMask ),
            static_cast< size_t >( frameCount ), flags,
            emptyCallback, 0 /* notificationFrames */,
            emptySharedBuffer, false /* threadCanCallJava */,
            0 /* AUDIO_SESSION_ID_GENERATE */,
            // TRANSFER_SYNC(3) 与 Media3 的成功路径一致。
            // 注意：SYNC 是阻塞写，**必须 play() 之后才 write**；open 阶段只调 set 不受影响。
            3 /* TRANSFER_SYNC */, nullptr /* offloadInfo */,
            t->attrSrc, attrs,
            false /* doNotReconnect */, 1.0f /* maxRequiredSpeed */, -1 /* reserved */ );
    leaveGuard();

    if( setResult != 0 )
    {
        g_lastSetResult = setResult;
        LOGW( "set() 拒绝 format=0x%x rate=%d frames=%d flags=0x%x → %d",
              format, sampleRate, frameCount, flags, setResult );
        // **不释放 `t->self`**：对象可能已部分构造（甚至已在 AudioFlinger 注册了 track）。
        // 析构半构造的 C++ 对象就是下一次崩溃；2 KB + 一个残留 track 比脏内存便宜。
        free( attrs );
        free( emptyCallback );
        free( emptySharedBuffer );
        free( t->attrSrc );
        free( t );
        return( kErrSetRejected );
    }

    free( attrs );
    free( emptyCallback );
    free( emptySharedBuffer );
    return( static_cast< jlong >( reinterpret_cast< uintptr_t >( t )));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dap_NativeDapDac_nativeWrite(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jobject buffer, jint size )
{
    Track* t = asTrack( handle );
    if( t == nullptr || t->self == nullptr )
    {
        return( kErrNotOpen );
    }
    void* addr = env->GetDirectBufferAddress( buffer );
    if( addr == nullptr )
    {
        return( kErrBufferNotDirect );
    }
    if( enterGuard() != 0 )
    {
        leaveGuard();
        return( kErrSetCrashed );
    }
    const int64_t written = g_api.write( t->self, addr, static_cast< size_t >( size ), false );
    leaveGuard();
    return( static_cast< jint >( written ));
}

extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dap_NativeDapDac_nativePlay(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle )
{
    Track* t = asTrack( handle );
    if( t == nullptr || t->self == nullptr )
    {
        return;
    }
    if( enterGuard() != 0 )
    {
        leaveGuard();
        return;
    }
    // 先音量再 start：部分 ROM 的 `AudioTrackExtImpl` 在 start 那一刻算好增益，
    // start 之后再 `setVolume` 会留在 -inf（`dumpsys media.audio_flinger` 的 `G db` 一栏）。
    if( g_api.setVolume != nullptr )
    {
        g_api.setVolume( t->self, 1.0f, 1.0f );
    }
    if( g_api.start != nullptr )
    {
        g_lastStartResult = g_api.start( t->self );
    }
    leaveGuard();
}

extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dap_NativeDapDac_nativePause(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle )
{
    Track* t = asTrack( handle );
    if( t == nullptr || t->self == nullptr || g_api.pause == nullptr )
    {
        return;
    }
    if( enterGuard() != 0 )
    {
        leaveGuard();
        return;
    }
    g_api.pause( t->self );
    leaveGuard();
}

extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dap_NativeDapDac_nativeFlush(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle )
{
    Track* t = asTrack( handle );
    if( t == nullptr || t->self == nullptr || g_api.flush == nullptr )
    {
        return;
    }
    if( enterGuard() != 0 )
    {
        leaveGuard();
        return;
    }
    g_api.flush( t->self );
    leaveGuard();
    // 播放头不因 flush 归零，所以**不清** wrapCount/positionLast ——
    // 与 Kotlin 侧 `UacRing.rebase(当前读数)` 的语义对齐
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dap_NativeDapDac_nativePositionFrames(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle )
{
    Track* t = asTrack( handle );
    if( t == nullptr || t->self == nullptr || g_api.getPosition == nullptr )
    {
        return( 0 );
    }
    if( enterGuard() != 0 )
    {
        leaveGuard();
        // 读位置崩了：返回上次已知值，不往上抛（位置错比进程消失便宜得多）
        return( static_cast< jlong >(
            ( static_cast< uint64_t >( t->wrapCount ) << 32 ) +
            static_cast< uint64_t >( t->positionLast )));
    }
    uint32_t cur = 0;
    const int32_t rc = g_api.getPosition( t->self, &cur );
    g_lastPositionResult = rc;
    leaveGuard();
    if( rc != 0 )
    {
        return( static_cast< jlong >(
            ( static_cast< uint64_t >( t->wrapCount ) << 32 ) +
            static_cast< uint64_t >( t->positionLast )));
    }
    // 32 位回绕累加。**只在"真的从顶上绕回底下"时才算回绕**：
    // 早期版本一律 `cur < positionLast → wrapCount++`，于是任意一次读数抖动
    // （这个 ROM 的 getPosition 会短暂给出小值/脏值）就被当成一整圈 2^32，
    // 位置直接跳到 4,295,427,535 —— 实测踩到，`hasPendingData` 从此恒 false，
    // Media3 认为播完了，再也不喂数。
    const bool realWrap = t->positionLast > 0xF0000000u && cur < 0x0FFFFFFFu;
    if( realWrap )
    {
        t->wrapCount += 1;
        t->positionLast = cur;
    }
    else if( cur >= t->positionLast )
    {
        t->positionLast = cur;
    }
    return( static_cast< jlong >(
        ( static_cast< uint64_t >( t->wrapCount ) << 32 ) +
        static_cast< uint64_t >( t->positionLast )));
}

/**
 * 这条轨**实际**跑在什么采样率上（`AudioTrack::getSampleRate()`）；拿不到就是 0。
 *
 * 为什么需要它：`set()` 返回 0 只证明参数被收下，**不证明 HAL 认了这个率**。
 * 实测这台 DAP 上"set 成功、字节按标称率被消费、`getPosition` 实时前进、但 DAC 静音"
 * 正是缺少这一层验证的结果（参考实现的建流判据就是 `rate == 请求值 || rate == 0`）。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dap_NativeDapDac_nativeSampleRate(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle )
{
    Track* t = asTrack( handle );
    if( t == nullptr || t->self == nullptr || g_api.getSampleRate == nullptr )
    {
        return( 0 );
    }
    if( enterGuard() != 0 )
    {
        leaveGuard();
        return( 0 );
    }
    const uint32_t rate = g_api.getSampleRate( t->self );
    leaveGuard();
    return( static_cast< jint >( rate ));
}

extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dap_NativeDapDac_nativeClosePart(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint part )
{
    Track* t = asTrack( handle );
    if( t == nullptr || t->self == nullptr )
    {
        return;
    }
    if( enterGuard() != 0 )
    {
        leaveGuard();
        return;
    }
    switch( part )
    {
        case 1: if( g_api.pause != nullptr ) g_api.pause( t->self ); break;
        case 2: if( g_api.flush != nullptr ) g_api.flush( t->self ); break;
        case 3: if( g_api.stop != nullptr ) g_api.stop( t->self ); break;
        case 4: if( g_api.dtor != nullptr ) g_api.dtor( t->self ); t->self = nullptr; break;
        default: break;
    }
    leaveGuard();
}

extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dap_NativeDapDac_nativeFree(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle )
{
    Track* t = asTrack( handle );
    if( t == nullptr )
    {
        return;
    }
    if( t->self != nullptr )
    {
        // 析构从未跑完时**不**释放对象内存：半析构的 C++ 对象再动一次只会更难查，
        // 而它只有 2 KB —— 泄漏比脏内存便宜。
        free( t->self );
        t->self = nullptr;
    }
    if( t->attrSrc != nullptr )
    {
        // 不调析构：`AttributionSourceState` 在这批 ROM 上没有导出析构（构造是 inline，
        // 析构同理）。它的成员在零填充状态下都是"空"，释放内存即可。
        free( t->attrSrc );
        t->attrSrc = nullptr;
    }
    free( t );
}
