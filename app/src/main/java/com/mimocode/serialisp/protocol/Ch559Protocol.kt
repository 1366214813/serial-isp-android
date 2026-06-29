package com.mimocode.serialisp.protocol

import com.mimocode.serialisp.hex.HexData
import com.mimocode.serialisp.usb.SerialPortManager
import kotlinx.coroutines.delay

class Ch559Protocol(serial: SerialPortManager, listener: ProtocolListener) : BaseProtocol(serial, listener) {

    override val name = "CH559 Bootloader"

    private fun ch559Checksum(data: ByteArray): Int {
        var cs = 0
        for (b in data) cs += b.toInt() and 0xFF
        return cs and 0xFF
    }

    private suspend fun cmdWrite(addr: Int, data: ByteArray): Boolean {
        val pkt = ByteArray(3 + data.size + 1)
        pkt[0] = 0xA0.toByte()
        pkt[1] = ((addr shr 8) and 0xFF).toByte()
        pkt[2] = (addr and 0xFF).toByte()
        data.copyInto(pkt, 3)
        pkt[3 + data.size] = ch559Checksum(pkt.copyOf(3 + data.size)).toByte()

        serial.write(pkt)
        val rsp = waitRawByte(500)
        return rsp == 0xA0
    }

    override suspend fun sync(): SyncResult? {
        log("=== CH559 Bootloader 同步 ===", "step")
        log("CH559 需要进入 Boot 模式: 上电时拉低 P3.6", "warn")

        for (attempt in 0 until 5) {
            rxBuf = ByteArray(0)
            val handsh = byteArrayOf(0xA0.toByte(), 0x7F, 0x7F, 0x7F, 0, 0, 0, 0, 0x11)
            serial.write(handsh)
            delay(20)
            val b = waitRawByte(500)
            if (b == 0xA0) {
                log("CH559 同步成功!", "ok")
                return SyncResult()
            }
            log("尝试 ${attempt + 1}/5...", "warn")
        }
        log("CH559 同步失败", "err")
        return null
    }

    override suspend fun detect(syncResult: SyncResult): ChipInfo {
        log("已连接 CH559 芯片", "ok")
        return ChipInfo("CH559")
    }

    override suspend fun program(hexData: HexData) {
        val d = hexData.data
        log("固件: ${d.size}B", "ok")

        // Step 1: 擦除
        log("[1/3] 擦除...", "step")
        rxBuf = ByteArray(0)
        val eraseCmd = byteArrayOf(0xA0.toByte(), 0, 0, 0x02, 0, 0, 0, 0, 0x02)
        serial.write(eraseCmd)
        val eraRsp = waitRawByte(2000)
        if (eraRsp != 0xA0) throw IllegalStateException("CH559 擦除失败")
        log("擦除 OK", "ok")
        setProgress(15)

        // Step 2: 写入
        log("[2/3] 写入代码...", "step")
        val chunkSize = 64
        val totalChunks = (d.size + chunkSize - 1) / chunkSize
        for (ci in 0 until totalChunks) {
            val offset = ci * chunkSize
            val end = minOf(offset + chunkSize, d.size)
            val chunk = d.copyOfRange(offset, end)
            val ok = cmdWrite(offset, chunk)
            if (!ok) throw IllegalStateException("CH559 写入失败 @0x${offset.toString(16)}")
            if ((ci + 1) % 10 == 0 || ci == totalChunks - 1) {
                log("${(ci + 1) * 100 / totalChunks}%", "data")
            }
            setProgress(15 + (ci + 1) * 75 / totalChunks)
        }

        // Step 3: 复位
        log("[3/3] 完成...", "step")
        rxBuf = ByteArray(0)
        val rstCmd = byteArrayOf(0xA0.toByte(), 0, 0, 0x01, 0, 0, 0, 0, 0x01)
        serial.write(rstCmd)
        waitRawByte(500)
        setProgress(100)
        log("CH559 烧录完成!", "ok")
    }
}
