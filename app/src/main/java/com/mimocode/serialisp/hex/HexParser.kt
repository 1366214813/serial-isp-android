package com.mimocode.serialisp.hex

data class HexData(
    val data: ByteArray,
    val size: Int,
    val written: Set<Int>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HexData) return false
        return size == other.size && data.contentEquals(other.data)
    }
    override fun hashCode(): Int = data.contentHashCode()
}

object HexParser {

    fun parseHex(text: String): HexData {
        val bytes = mutableListOf<Int>()
        val written = mutableSetOf<Int>()
        var ext = 0
        var size = 0

        for (raw in text.lines()) {
            val line = raw.trim()
            if (!line.startsWith(":")) continue

            val bc = line.substring(1, 3).toInt(16)
            val addr = line.substring(3, 7).toInt(16)
            val type = line.substring(7, 9).toInt(16)

            when (type) {
                4 -> { ext = line.substring(9, 13).toInt(16) shl 16; continue }
                2 -> { ext = line.substring(9, 13).toInt(16) shl 4; continue }
                0 -> {}
                else -> continue
            }

            val abs = ext + addr
            for (i in 0 until bc) {
                val idx = abs + i
                while (bytes.size <= idx) bytes.add(0xFF)
                bytes[idx] = line.substring(9 + i * 2, 11 + i * 2).toInt(16)
                if (idx + 1 > size) size = idx + 1
                written.add(idx)
            }
        }

        val data = ByteArray(size) { if (it < bytes.size) bytes[it].toByte() else 0xFF.toByte() }
        return HexData(data, size, written)
    }

    fun parseBin(data: ByteArray): HexData {
        val written = (0 until data.size).toSet()
        return HexData(data, data.size, written)
    }

    fun hexStr(buf: ByteArray, n: Int = buf.size): String {
        return buf.take(n).joinToString(" ") { "%02X".format(it) }
    }
}
