package com.annieplayer.annie_music.kt.core.audio

import com.annieplayer.annie_music.kt.core.audio.dsp.NativeOboeStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmapProfilesTest {

    private val policy = """
        <audioPolicyConfiguration version="7.0">
            <mixPorts>
                <mixPort name="primary output" role="source" flags="AUDIO_OUTPUT_FLAG_PRIMARY">
                    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
                             samplingRates="44100 48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
                </mixPort>
                <mixPort name="mmap_no_irq_out" role="source" flags="AUDIO_OUTPUT_FLAG_DIRECT AUDIO_OUTPUT_FLAG_MMAP_NOIRQ">
                    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
                             samplingRates="48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
                </mixPort>
                <mixPort name="hifi_playback" role="source" />
            </mixPorts>
            <routes>
                <route type="mix" sink="Speaker"
                       sources="primary output,deep_buffer,mmap_no_irq_out"/>
            </routes>
        </audioPolicyConfiguration>
    """.trimIndent()

    @Test
    fun `只认 MMAP 端口的 profile 不把普通 mixPort 算进来`() {
        val profiles = parseMmapProfiles(policy)
        assertEquals(1, profiles.size)
        assertEquals(48_000, profiles[0].sampleRate)
        assertEquals(NativeOboeStream.FORMAT_I16, profiles[0].format)
    }

    @Test
    fun `MMAP 端口声明的每个率都要收 多个格式分开记`() {
        val multi = """
            <mixPort name="mmap_no_irq_out" role="source" flags="AUDIO_OUTPUT_FLAG_MMAP_NOIRQ">
                <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
                         samplingRates="44100 48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
                <profile name="" format="AUDIO_FORMAT_PCM_FLOAT"
                         samplingRates="48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
                <profile name="" format="AUDIO_FORMAT_PCM_32_BIT"
                         samplingRates="48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
            </mixPort>
        """.trimIndent()
        val profiles = parseMmapProfiles(multi)
        assertEquals(
            listOf(
                MmapProfile(44_100, NativeOboeStream.FORMAT_I16),
                MmapProfile(48_000, NativeOboeStream.FORMAT_I16),
                MmapProfile(48_000, NativeOboeStream.FORMAT_FLOAT),
            ),
            profiles,
        )
    }

    @Test
    fun `24 bit packed 与 8_24 bit 的映射不同`() {
        assertEquals(NativeOboeStream.FORMAT_I24, aaudioFormatOfPolicy("AUDIO_FORMAT_PCM_24_BIT_PACKED"))
        assertEquals(null, aaudioFormatOfPolicy("AUDIO_FORMAT_PCM_8_24_BIT"))
        assertEquals(null, aaudioFormatOfPolicy("AUDIO_FORMAT_DSD_16_BIT"))
        assertEquals(NativeOboeStream.FORMAT_FLOAT, aaudioFormatOfPolicy("AUDIO_FORMAT_PCM_FLOAT"))
    }

    @Test
    fun `策略里没有 MMAP 端口时返回空`() {
        val noMmap = """
            <mixPort name="deep_buffer" role="source" flags="AUDIO_OUTPUT_FLAG_DEEP_BUFFER">
                <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
                         samplingRates="44100 48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
            </mixPort>
        """.trimIndent()
        assertTrue(parseMmapProfiles(noMmap).isEmpty())
    }

    @Test
    fun `读不到策略文件时返回空而不是抛异常`() {
        val missing = java.io.File("/definitely/not/here/audio_policy_configuration.xml")
        assertTrue(mmapProfilesFromPolicy(listOf(missing)).isEmpty())
    }
}
