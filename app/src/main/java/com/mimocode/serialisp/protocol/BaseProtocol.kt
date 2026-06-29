package com.mimocode.serialisp.protocol

import com.mimocode.serialisp.hex.HexData
import com.mimocode.serialisp.usb.SerialPortManager
import kotlinx.coroutines.delay

data class SyncResult(val raw: ByteArray? = null)
data class ChipInfo(val name: String, val extra: Map<String, String> = emptyMap())
data class Packet(val dir: Int, val len: Int, val payload: ByteArray, val rawLen: Int)

interface ProtocolListener {
    fun onLog(message: String, level: String = "info")
    fun onProgress(percent: Int)
}

abstract class BaseProtocol(protected val serial: SerialPortManager, protected val listener: ProtocolListener) {

    abstract val name: String

    abstract suspend fun sync(): SyncResult?
    abstract suspend fun detect(syncResult: SyncResult): ChipInfo?
    abstract suspend fun program(hexData: HexData)

    protected var rxBuf = ByteArray(0)

    protected fun log(msg: String, level: String = "info") {
        listener.onLog(msg, level)
    }

    protected fun setProgress(percent: Int) {
        listener.onProgress(percent)
    }

    protected suspend fun waitPacket(timeoutMs: Long): Packet? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val pkt = parsePacket(rxBuf)
            if (pkt != null) return pkt
            delay(10)
        }
        return null
    }

    protected suspend fun waitRawByte(timeoutMs: Long): Int {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (rxBuf.isNotEmpty()) {
                val b = rxBuf[0].toInt() and 0xFF
                rxBuf = if (rxBuf.size > 1) rxBuf.copyOfRange(1, rxBuf.size) else ByteArray(0)
                return b
            }
            delay(5)
        }
        return -1
    }

    protected fun appendRx(data: ByteArray) {
        rxBuf += data
    }

    fun onReceiveData(data: ByteArray) {
        appendRx(data)
    }

    protected fun parsePacket(buf: ByteArray): Packet? {
        for (i in 0 until buf.size - 4) {
            if (buf[i].toInt() and 0xFF != 0xB9) continue
            if (i > 0 && buf[i - 1].toInt() and 0xFF == 0x00) continue
            val off = i + 1
            if (off + 2 > buf.size) continue
            val dir = buf[off].toInt() and 0xFF
            if (dir != 0x68 && dir != 0x6A) continue
            var pos = off + 1
            if (pos >= buf.size) continue
            if (buf[pos].toInt() and 0xFF == 0x00) pos++
            if (pos >= buf.size) continue
            val len = buf[pos].toInt() and 0xFF
            val plen = len - 6
            if (plen < 0) continue
            val end = pos + 1 + plen + 2
            if (end > buf.size) continue
            if (buf[end - 1].toInt() and 0xFF != 0x16) continue
            val pay = buf.copyOfRange(pos + 1, end - 2)
            var cs = len + dir
            for (b in pay) cs += b.toInt() and 0xFF
            if (buf[end - 3].toInt() and 0xFF != (cs shr 8 and 0xFF)) continue
            if (buf[end - 2].toInt() and 0xFF != (cs and 0xFF)) continue
            return Packet(dir, len, pay, pay.size + 9)
        }
        return null
    }

    protected fun buildPkt(dir: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 6
        var cs = len + dir
        for (b in payload) cs += b.toInt() and 0xFF
        val pkt = ByteArray(9 + payload.size)
        pkt[0] = 0x46; pkt[1] = 0xB9.toByte(); pkt[2] = dir.toByte()
        pkt[3] = 0; pkt[4] = len.toByte()
        payload.copyInto(pkt, 5)
        pkt[5 + payload.size] = ((cs shr 8) and 0xFF).toByte()
        pkt[6 + payload.size] = (cs and 0xFF).toByte()
        pkt[7 + payload.size] = 0x16
        return pkt
    }
}
