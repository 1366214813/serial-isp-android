package com.mimocode.serialisp.protocol

import com.mimocode.serialisp.hex.HexData
import com.mimocode.serialisp.usb.SerialPortManager
import kotlinx.coroutines.delay

class StcProtocol(serial: SerialPortManager, listener: ProtocolListener) : BaseProtocol(serial, listener) {

    override val name = "STC ISP"

    private val chipDB = mapOf(
        0x0022 to "STC89C52RC", 0x00F2 to "STC89C58RD+",
        0xF1E1 to "STC12C5A60S2", 0xF294 to "STC15F104W",
        0xF7E3 to "STC32G12K128", 0xF784 to "STC8H8K64U",
        0x0B87 to "STC8系列", 0x78B4 to "AI8051U",
        0xF78D to "STC8G2K64S4", 0xF72F to "STC15W4K32S4",
        0xF7D6 to "STC32G10K128"
    )

    private var statusPkt: Packet? = null

    override suspend fun sync(): SyncResult? {
        log("=== STC ISP 同步 ===", "step")
        log("请确保目标板已上电，必要时断电再上电触发 ISP", "warn")
        rxBuf = ByteArray(0)

        for (pulse in 0 until 10000) {
            serial.write(byteArrayOf(0x7F))
            delay(1)

            while (rxBuf.isNotEmpty()) {
                val pkt = parsePacket(rxBuf)
                if (pkt != null) {
                    if (pkt.payload[0].toInt() and 0xFF == 0x50) {
                        log("ISP 同步成功! (脉冲: $pulse)", "ok")
                        statusPkt = pkt
                        return SyncResult()
                    }
                }
            }
            if (pulse % 1000 == 0 && pulse > 0) {
                log("已发送 $pulse 次脉冲...", "warn")
            }
        }
        log("STC ISP 同步失败", "err")
        return null
    }

    override suspend fun detect(syncResult: SyncResult): ChipInfo? {
        val pkt = statusPkt ?: return null
        if (pkt.payload.size < 22) {
            log("数据包过短", "err")
            return null
        }

        val magic = (pkt.payload[20].toInt() and 0xFF) shl 8 or (pkt.payload[21].toInt() and 0xFF)
        val bsl = "${(pkt.payload[17].toInt() and 0xFF) shr 4}.${pkt.payload[17].toInt() and 0x0F}"
        val name = chipDB[magic] ?: "未知 (0x${magic.toString(16).padStart(4, '0')})"

        log("芯片: $name | BSL: v$bsl | Magic: 0x${magic.toString(16).padStart(4, '0')}", "ok")
        return ChipInfo(name, mapOf("magic" to magic.toString(16), "bsl" to bsl))
    }

    override suspend fun program(hexData: HexData) {
        val pkt = statusPkt ?: throw IllegalStateException("请先检测芯片")
        val bs = 128
        val d = hexData.data

        val addrs = hexData.written.sorted()
        val blkSet = addrs.map { it / bs }.toSet()
        val blks = blkSet.sorted()

        log("共 ${blks.size} 块, 有效 ${hexData.written.size}B", "ok")

        val arg = if (pkt.payload.size > 4) pkt.payload[4].toInt() and 0xFF else 0x00
        val baud = 115200 // TODO: get from settings

        // Step 1: 设置参数
        log("[1/5] 设置参数...", "step")
        val RL = 65536 - Math.round(24000000.0 / (baud * 4)).toInt()
        rxBuf = ByteArray(0)
        val setParamPkt = buildPkt(0x6A, byteArrayOf(
            0x01, arg.toByte(), 0x40, ((RL shr 8) and 0xFF).toByte(), (RL and 0xFF).toByte(), 0, 0, 0xC3.toByte()
        ))
        serial.write(setParamPkt)
        val rp = waitPacket(500)
        if (rp == null || rp.payload[0].toInt() and 0xFF != 0x01) {
            throw IllegalStateException("设置参数失败")
        }
        log("参数设置 OK", "ok")

        // 切换波特率
        serial.setParameters(baud, com.mimocode.serialisp.usb.Parity.EVEN)
        setProgress(15)

        // Step 2: 准备编程
        log("[2/5] 准备编程...", "step")
        rxBuf = ByteArray(0)
        serial.write(buildPkt(0x6A, byteArrayOf(0x05, 0, 0, 0x5A, 0xA5.toByte())))
        val rp2 = waitPacket(300)
        if (rp2 == null || rp2.payload[0].toInt() and 0xFF != 0x05) {
            throw IllegalStateException("准备编程失败")
        }
        log("准备编程 OK", "ok")
        setProgress(25)

        // Step 3: 擦除
        log("[3/5] 擦除 Flash...", "step")
        rxBuf = ByteArray(0)
        serial.write(buildPkt(0x6A, byteArrayOf(0x03, 0, 0, 0x5A, 0xA5.toByte())))
        val rp3 = waitPacket(3000)
        if (rp3 == null || rp3.payload[0].toInt() and 0xFF != 0x03) {
            throw IllegalStateException("擦除失败")
        }
        log("擦除 OK", "ok")
        setProgress(35)

        // Step 4: 写入
        log("[4/5] 写入代码...", "step")
        for (bi in blks.indices) {
            val i = blks[bi]
            val a = i * bs
            val b = ByteArray(bs) { j ->
                if (a + j < d.size) d[a + j] else 0xFF.toByte()
            }
            var cmd = 0x22
            if ((a shr 16 and 0xFF) == 0xFE) cmd = cmd or 0x10

            val p = ByteArray(5 + bs)
            p[0] = cmd.toByte()
            p[1] = ((a shr 8) and 0xFF).toByte()
            p[2] = (a and 0xFF).toByte()
            p[3] = 0x5A; p[4] = 0xA5.toByte()
            b.copyInto(p, 5)

            rxBuf = ByteArray(0)
            serial.write(buildPkt(0x6A, p))
            val rp4 = waitPacket(3000)
            if (rp4 == null || rp4.payload[0].toInt() and 0xFF != 0x02 || rp4.payload[1].toInt() and 0xFF != 0x54) {
                throw IllegalStateException("写入失败 @0x${a.toString(16)}")
            }
            if ((bi + 1) % 10 == 0 || bi == blks.lastIndex) {
                log("已写 ${bi + 1}/${blks.size} 块", "data")
            }
            setProgress(35 + (bi + 1) * 60 / blks.size)
        }
        log("写入完成", "ok")

        // Step 5: 复位
        log("[5/5] 复位芯片...", "step")
        rxBuf = ByteArray(0)
        serial.write(buildPkt(0x6A, byteArrayOf(0x82.toByte())))
        setProgress(100)
        log("STC 烧录完成!", "ok")
    }
}
