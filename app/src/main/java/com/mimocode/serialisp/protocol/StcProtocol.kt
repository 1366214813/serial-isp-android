package com.mimocode.serialisp.protocol

import com.mimocode.serialisp.hex.HexData
import com.mimocode.serialisp.usb.Parity
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
        log("先以 2400 波特率连接...", "warn")
        log("请在同步期间给目标板上电 (断电→上电)", "warn")

        // 切到 2400 波特率
        serial.setParameters(2400, Parity.EVEN)
        delay(50)
        synchronized(this) { rxBuf = ByteArray(0); rxBufLen = 0 }

        val startTime = System.currentTimeMillis()
        val timeoutMs = 20000L
        var lastLogTime = 0L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // 发送 0x7F 同步脉冲
            serial.write(byteArrayOf(0x7F))
            delay(1)

            synchronized(this) {
                if (rxBufLen > 0) {
                    val dump = hexDump(rxBuf)
                    log("RX [$rxBufLen]: $dump", "data")
                }

                // 直接检查第一个字节是否为 0x50 (同步响应)
                if (rxBufLen > 0 && (rxBuf[0].toInt() and 0xFF) == 0x50) {
                    val elapsed = System.currentTimeMillis() - startTime
                    log("ISP 同步成功! (${elapsed}ms)", "ok")
                    // 保存原始数据作为 statusPkt
                    val payload = ByteArray(rxBufLen)
                    System.arraycopy(rxBuf, 0, payload, 0, rxBufLen)
                    statusPkt = Packet(0x68, rxBufLen + 6, payload, rxBufLen)
                    consumeBytes(rxBufLen)
                    return SyncResult()
                }

                // 也尝试解析完整包
                val pkt = parsePacket(rxBuf)
                if (pkt != null) {
                    consumeBytes(pkt.rawLen)
                    if (pkt.payload.isNotEmpty() && pkt.payload[0].toInt() and 0xFF == 0x50) {
                        val elapsed = System.currentTimeMillis() - startTime
                        log("ISP 同步成功! (${elapsed}ms)", "ok")
                        statusPkt = pkt
                        return SyncResult()
                    }
                    log("收到包: dir=0x${pkt.dir.toString(16)} payload[0]=0x${if(pkt.payload.isNotEmpty()) (pkt.payload[0].toInt() and 0xFF).toString(16) else "?"}", "data")
                } else if (rxBufLen > 0) {
                    // 如果不是有效包但有数据，显示前几个字节
                    if (rxBufLen >= 3) {
                        log("非包数据: ${hexDump(rxBuf, 16)}", "data")
                    }
                    consumeBytes(rxBufLen)
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastLogTime > 3000) {
                val elapsed = ((now - startTime) / 1000).toInt()
                log("同步中... ${elapsed}s (请确保目标板正在上电)", "warn")
                lastLogTime = now
            }
        }
        log("STC ISP 同步超时 (${(timeoutMs / 1000).toInt()}s)", "err")
        log("排查: 1) CH340 TX→RX交叉 2) 2400+8E1 3) 目标板同步期间上电", "warn")
        return null
    }

    override suspend fun detect(syncResult: SyncResult): ChipInfo? {
        val pkt = statusPkt ?: return null
        if (pkt.payload.size < 22) {
            log("状态数据过短: ${pkt.payload.size}B", "err")
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
        val targetBaud = 115200
        val fosc = 24000000

        // Step 1: 设置参数 (0x01)
        log("[1/5] 设置参数...", "step")
        val RL = 65536 - Math.round(fosc.toDouble() / (targetBaud * 4)).toInt()
        synchronized(this) { rxBuf = ByteArray(0); rxBufLen = 0 }
        val setParamPayload = byteArrayOf(
            0x01, arg.toByte(), 0x40,
            ((RL shr 8) and 0xFF).toByte(), (RL and 0xFF).toByte(),
            0, 0, 0xC3.toByte()
        )
        serial.write(buildPkt(0x6A, setParamPayload))
        val rp = waitPacket(500)
        if (rp == null || rp.payload[0].toInt() and 0xFF != 0x01) {
            throw IllegalStateException("设置参数失败")
        }
        log("参数设置 OK, RL=$RL", "ok")

        // 关闭串口，以 115200 重新打开
        log("切换波特率到 $targetBaud...", "step")
        serial.reconnect(targetBaud, Parity.EVEN)
        delay(100)
        synchronized(this) { rxBuf = ByteArray(0); rxBufLen = 0 }
        setProgress(15)

        // Step 2: 准备编程 (0x05)
        log("[2/5] 准备编程...", "step")
        serial.write(buildPkt(0x6A, byteArrayOf(0x05, 0, 0, 0x5A, 0xA5.toByte())))
        val rp2 = waitPacket(300)
        if (rp2 == null || rp2.payload[0].toInt() and 0xFF != 0x05) {
            throw IllegalStateException("准备编程失败")
        }
        log("准备编程 OK", "ok")
        setProgress(25)

        // Step 3: 擦除 (0x03)
        log("[3/5] 擦除 Flash...", "step")
        synchronized(this) { rxBuf = ByteArray(0); rxBufLen = 0 }
        serial.write(buildPkt(0x6A, byteArrayOf(0x03, 0, 0, 0x5A, 0xA5.toByte())))
        val rp3 = waitPacket(3000)
        if (rp3 == null || rp3.payload[0].toInt() and 0xFF != 0x03) {
            throw IllegalStateException("擦除失败")
        }
        log("擦除 OK", "ok")
        setProgress(35)

        // Step 4: 写入 (0x22)
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

            synchronized(this) { rxBuf = ByteArray(0); rxBufLen = 0 }
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

        // Step 5: 复位 (0x82)
        log("[5/5] 复位芯片...", "step")
        synchronized(this) { rxBuf = ByteArray(0); rxBufLen = 0 }
        serial.write(buildPkt(0x6A, byteArrayOf(0x82.toByte())))
        setProgress(100)
        log("STC 烧录完成!", "ok")
    }
}
