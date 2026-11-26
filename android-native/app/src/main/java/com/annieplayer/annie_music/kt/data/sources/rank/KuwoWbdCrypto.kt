package com.annieplayer.annie_music.kt.data.sources.rank

import com.annieplayer.annie_music.kt.data.sources.common.encodeComponent
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

private const val kuwoWbdAppId = "y67sprxhhpws"

private val kuwoWbdKey: ByteArray = byteArrayOf(
    112, 87, 39, 61, 199.toByte(), 250.toByte(), 41, 191.toByte(),
    57, 68, 45, 114, 221.toByte(), 94, 140.toByte(), 228.toByte(),
)

private fun _kuwoWbdCipher(mode: Int): Cipher =
    Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
        init(mode, SecretKeySpec(kuwoWbdKey, "AES"))
    }

internal fun kuwoWbdEncrypt(json: String): String =
    _kuwoWbdCipher(Cipher.ENCRYPT_MODE)
        .doFinal(json.toByteArray(Charsets.UTF_8))
        .toByteString()
        .base64()

internal fun kuwoWbdSign(data: String, time: Long): String =
    MessageDigest.getInstance("MD5")
        .digest((kuwoWbdAppId + data + time).toByteArray(Charsets.UTF_8))
        .toByteString()
        .hex()
        .uppercase()

internal fun kuwoWbdBuildParam(json: String, time: Long): String {
    val data = kuwoWbdEncrypt(json)
    return "data=" + encodeComponent(data) +
        "&time=" + time +
        "&appId=" + kuwoWbdAppId +
        "&sign=" + kuwoWbdSign(data, time)
}

internal fun kuwoWbdDecodeData(body: String): String {
    val bytes = _kuwoWbdPercentDecode(body.trim()).decodeBase64()?.toByteArray()
        ?: throw IllegalStateException("酷我榜单响应不是 base64")
    return String(_kuwoWbdCipher(Cipher.DECRYPT_MODE).doFinal(bytes), Charsets.UTF_8)
}

private fun _kuwoWbdPercentDecode(s: String): String {
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length) {
            val code = s.substring(i + 1, i + 3).toIntOrNull(16)
            if (code != null) {
                out.append(code.toChar())
                i += 3
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}
