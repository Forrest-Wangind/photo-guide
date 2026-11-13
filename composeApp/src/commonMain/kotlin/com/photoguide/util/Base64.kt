package com.photoguide.util

/**
 * 自带的 Base64 编解码器。
 *
 * 为什么不用 kotlin.io.encoding.Base64：那个 API 在 Kotlin 各版本间长期挂在
 * @ExperimentalEncodingApi 下，版本一变就可能编不过。这里六十行、零 API 依赖风险。
 *
 * 两个方向都在用：编码（照片 data URL）、解码（历史记录里的缩略图，存在
 * prefs 里只有文本能放）。
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

    /**
     * 解码。**不认识就返回 null，绝不抛异常** ——
     * 调用方（历史列表）据此降级成"无图"，而不会因为一条脏数据整页崩掉。
     *
     * 宽容三处：忽略换行/空格（有些编码器会插行）、忽略 `=` 填充
     * （按剩余位数自然截断，不靠填充符号判断长度）、非法字符直接判失败。
     */
    fun decode(text: String): ByteArray? {
        if (text.isEmpty()) return null
        val out = ByteArray(text.length / 4 * 3 + 3)
        var outLen = 0
        var buffer = 0
        var bits = 0
        for (c in text) {
            if (c == '\n' || c == '\r' || c == ' ' || c == '=') continue
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outLen++] = ((buffer shr bits) and 0xFF).toByte()
                // 只留还没消费的低位，否则高位残渣越滚越多，取出的字节会串
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.copyOf(outLen)
    }
}
