package com.photoguide.util

/**
 * 自带的 Base64 编码器。
 *
 * 为什么不用 kotlin.io.encoding.Base64：那个 API 在 Kotlin 各版本间长期挂在
 * @ExperimentalEncodingApi 下，版本一变就可能编不过。这里 30 行、零 API 依赖风险，
 * 而且我们只用来编码 JPEG，不需要 decode。
 */
object Base64 {
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i + 2 < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            val b2 = data[i + 2].toInt() and 0xFF
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            sb.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)])
            sb.append(ALPHABET[b2 and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val b0 = data[i].toInt() and 0xFF
                sb.append(ALPHABET[b0 shr 2])
                sb.append(ALPHABET[(b0 and 0x03) shl 4])
                sb.append("==")
            }
            2 -> {
                val b0 = data[i].toInt() and 0xFF
                val b1 = data[i + 1].toInt() and 0xFF
                sb.append(ALPHABET[b0 shr 2])
                sb.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
                sb.append(ALPHABET[(b1 and 0x0F) shl 2])
                sb.append('=')
            }
        }
        return sb.toString()
    }
}
