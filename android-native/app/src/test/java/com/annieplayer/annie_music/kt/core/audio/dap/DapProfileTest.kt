package com.annieplayer.annie_music.kt.core.audio.dap

import org.junit.Assert.assertEquals
import org.junit.Test

class DapProfileTest {

    private fun detect(
        manufacturer: String? = null,
        brand: String? = null,
        model: String? = null,
        product: String? = null,
        exists: (String) -> Boolean = { false },
    ) = detectDapVendor(manufacturer, brand, model, product, exists)

    @Test
    fun `Build 字段命中厂商标记`() {
        assertEquals(DapVendor.fiio, detect(manufacturer = "FiiO"))
        assertEquals(DapVendor.hiby, detect(brand = "HiBy"))
        assertEquals(DapVendor.ibasso, detect(manufacturer = "iBasso"))
        assertEquals(DapVendor.shanling, detect(manufacturer = "Shanling"))
        assertEquals(DapVendor.onix, detect(brand = "Onix"))
        assertEquals(DapVendor.cayin, detect(manufacturer = "Cayin"))
        assertEquals(DapVendor.tempotec, detect(manufacturer = "TempoTec"))
    }

    @Test
    fun `字段大小写与首尾空格不影响识别`() {
        assertEquals(DapVendor.fiio, detect(model = "  fiio m11s  "))
        assertEquals(DapVendor.hiby, detect(product = "HIBY R6"))
    }

    @Test
    fun `飞傲的库文件存在即可识别——Build 字段可以是通用值`() {
        // 有些飞傲 ROM 的 MANUFACTURER 是 "FiiO"，但也有只留库文件名的版本
        val hit = detect(
            manufacturer = "unknown",
            brand = "unknown",
            exists = { it == "/vendor/lib64/libfiioaudio.so" },
        )
        assertEquals(DapVendor.fiio, hit)
    }

    @Test
    fun `四个飞傲库路径里任意一个存在都算`() {
        for (path in FIIO_MARKER_FILES) {
            val vendor = detect(manufacturer = "generic", exists = { it == path })
            assertEquals("path=$path", DapVendor.fiio, vendor)
        }
    }

    @Test
    fun `Astell 的四种字段写法都归一到同一个厂商`() {
        assertEquals(DapVendor.astell, detect(manufacturer = "Astell&Kern"))
        assertEquals(DapVendor.astell, detect(model = "astell & kern sp3000"))
        assertEquals(DapVendor.astell, detect(product = "astellnkern"))
        assertEquals(DapVendor.astell, detect(brand = "Astell"))
    }

    @Test
    fun `Sony 只认 Walkman 型号——Xperia 不算 DAP`() {
        assertEquals(DapVendor.sony, detect(manufacturer = "Sony", model = "NW-ZX707"))
        assertEquals(DapVendor.sony, detect(manufacturer = "Sony", model = "NW-WM1AM2"))
        assertEquals(DapVendor.sony, detect(manufacturer = "Sony", model = "NW-A306"))
        // XQ- 是 Xperia 的型号前缀，它没有这条内置 DAC 通路
        assertEquals(DapVendor.unknown, detect(manufacturer = "Sony", model = "XQ-DQ72"))
        // 只有 sony 而没有型号信息时不确定，不能猜
        assertEquals(DapVendor.unknown, detect(manufacturer = "Sony"))
    }

    @Test
    fun `什么都没有时是 unknown 而不是某个默认厂商`() {
        assertEquals(DapVendor.unknown, detect())
        assertEquals(DapVendor.unknown, detect(manufacturer = "", brand = null, model = " ", product = null))
    }

    @Test
    fun `unknown 的 isDap 是 false——但那只表示没认出来`() {
        assertEquals(false, DapVendor.unknown.isDap)
        assertEquals(true, DapVendor.fiio.isDap)
        assertEquals(true, DapVendor.sony.isDap)
    }
}
