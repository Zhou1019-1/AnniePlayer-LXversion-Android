package com.annieplayer.annie_music.kt.data.sources.rank

import org.junit.Assert.assertEquals
import org.junit.Test

class KuwoWbdCryptoTest {

    private val json =
        """{"uid":"","devId":"","sFrom":"kuwo_sdk","user_type":"AP","carSource":"kwplayercar_ar_6.0.1.0_apk_keluze.apk","id":"93","pn":0,"rn":5}"""

    private val time = 1700000000000L

    private val data =
        "WkMIYYRh9KUzeRRawjXXaoMHen4RKh1V8Fy+lzIzNADTo666ORiMDeHjlF3tGF1DepboQy7YV9oPoe2hryX1o7hmVXa4ujFXpqu5jktJwVK3WTe8ueldViwbQG5VjKWlpXwLzByjOVdZyaNRJv4Q8gXL13wm9/T8WlIYYzxXlkf9oxoD67pEPqRtrUdLt9pp"

    private val sign = "060247F5EB5C95D05B06857BC4DB89EB"

    @Test
    fun encrypt_matches_reference_ciphertext() {
        assertEquals(data, kuwoWbdEncrypt(json))
    }

    @Test
    fun sign_matches_reference_digest() {
        assertEquals(sign, kuwoWbdSign(data, time))
    }

    @Test
    fun build_param_encodes_data_and_carries_sign() {
        val param = kuwoWbdBuildParam(json, time)
        assertEquals(
            "data=" + data.replace("+", "%2B").replace("/", "%2F") +
                "&time=" + time +
                "&appId=y67sprxhhpws" +
                "&sign=" + sign,
            param,
        )
    }

    @Test
    fun decode_data_round_trips_reference_ciphertext() {
        val body = data.replace("+", "%2B").replace("/", "%2F")
        assertEquals(json, kuwoWbdDecodeData(body))
    }

    @Test
    fun decode_data_accepts_unencoded_body() {
        assertEquals(json, kuwoWbdDecodeData(data))
    }
}
