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

    private var statusPacket: ByteArray? = null
    private var handshakeBaud = 2400
    private var transferBaud = 115200

    override suspend fun sync(): SyncResult? {
        log("=== STC ISP 同步 ===", "step")
        log("握手波特率: $handshakeBaud, 无校验", "data")

        // 以 2400 波特率连接，无校验
        serial.setParameters(handshakeBaud, Parity.NONE)
        delay(100)
        clearRxBuf()

        val startTime = System.currentTimeMillis()
        val timeoutMs = 30000L
        var lastLogTime = 0L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // 发送 0x7F 同步脉冲 (stcgal 每 30ms 发一个)
            serial.write(byteArrayOf(0x7F))
            delay(30)

            // 检查是否有响应
            synchronized(this) {
                if (rxBufLen > 0) {
                    val dump = hexDump(rxBuf, 48)
                    log("RX [$rxBufLen]: $dump", "data")
                }
            }

            // 尝试解析状态包
            val statusPkt = synchronized(this) { parsePacket(rxBuf) }
            if (statusPkt != null) {
                consumeBytes(statusPkt.rawLen)
                val firstByte = if (statusPkt.payload.isNotEmpty()) statusPkt.payload[0].toInt() and 0xFF else 0

                if (firstByte == 0x50) {
                    // 新芯片直接返回 0x50
                    log("收到状态包 (0x50)", "ok")
                    statusPacket = statusPkt.payload
                    return SyncResult()
                } else if (firstByte == 0x80) {
                    // 需要重新确认
                    log("收到 0x80, 发送确认...", "warn")
                    // 切换到偶校验
                    serial.setParameters(handshakeBaud, Parity.EVEN)
                    delay(10)
                    // 发送确认包
                    val ackPkt = buildPkt(0x6A, byteArrayOf(0x80.toByte(), 0x00, 0xF1.toByte()))
                    clearRxBuf()
                    serial.write(ackPkt)
                    delay(100)
                    // 继续发送脉冲等待
                    continue
                } else if (firstByte == 0x00) {
                    // STC89 系列
                    log("收到状态包 (0x00)", "ok")
                    statusPacket = statusPkt.payload
                    return SyncResult()
                } else {
                    log("未知响应: 0x${firstByte.toString(16)}", "data")
                }
            } else if (rxBufLen > 0) {
                // 有数据但不是完整包，检查第一个字节
                synchronized(this) {
                    if (rxBufLen > 0) {
                        val b = rxBuf[0].toInt() and 0xFF
                        if (b == 0x50) {
                            // 可能是简短的状态响应
                            log("收到 0x50 (无包头)", "ok")
                            val payload = ByteArray(rxBufLen)
                            System.arraycopy(rxBuf, 0, payload, 0, rxBufLen)
                            statusPacket = payload
                            consumeBytes(rxBufLen)
                            return SyncResult()
                        } else if (b == 0x80) {
                            log("收到 0x80 (无包头)", "warn")
                            consumeBytes(rxBufLen)
                        }
                    }
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastLogTime > 3000) {
                val elapsed = ((now - startTime) / 1000).toInt()
                log("同步中... ${elapsed}s (请给目标板上电)", "warn")
                lastLogTime = now
            }
        }
        log("STC ISP 同步超时", "err")
        return null
    }

    override suspend fun detect(syncResult: SyncResult): ChipInfo? {
        val pkt = statusPacket ?: return null
        if (pkt.size < 23) {
            log("状态数据过短: ${pkt.size}B (需要>=23)", "err")
            return null
        }

        val magic = (pkt[20].toInt() and 0xFF) shl 8 or (pkt[21].toInt() and 0xFF)
        val bsl = "${(pkt[17].toInt() and 0xFF) shr 4}.${pkt[17].toInt() and 0x0F}"
        val name = chipDB[magic] ?: "未知 (0x${magic.toString(16).padStart(4, '0')})"

        log("芯片: $name | BSL: v$bsl | Magic: 0x${magic.toString(16).padStart(4, '0')}", "ok")
        return ChipInfo(name, mapOf("magic" to magic.toString(16), "bsl" to bsl))
    }

    override suspend fun program(hexData: HexData) {
        val statusPkt = statusPacket ?: throw IllegalStateException("请先检测芯片")

        // 根据芯片类型选择波特率设置方式
        val firstByte = statusPkt[0].toInt() and 0xFF
        if (firstByte == 0x50) {
            // 新芯片: 发送 0x01 命令设置波特率
            log("[1/5] 设置传输波特率...", "step")
            val fosc = 24000000.0
            val RL = 65536 - Math.round(fosc / (transferBaud * 4)).toInt()
            clearRxBuf()
            serial.write(buildPkt(0x6A, byteArrayOf(
                0x01, 0x00, 0x00,
                ((RL shr 8) and 0xFF).toByte(), (RL and 0xFF).toByte(),
                0x00, 0x00, 0xC3.toByte()
            )))
            val rp = waitPacket(1000)
            if (rp != null && rp.payload[0].toInt() and 0xFF == 0x01) {
                log("波特率设置 OK", "ok")
            } else {
                log("波特率设置响应: ${rp?.payload?.get(0)?.toInt()?.toString(16) ?: "超时"}", "data")
            }
        }

        // 切换到 115200 + 偶校验
        log("切换到 $transferBaud + 偶校验...", "step")
        serial.reconnect(transferBaud, Parity.EVEN)
        delay(100)
        clearRxBuf()

        val bs = 128
        val d = hexData.data

        val addrs = hexData.written.sorted()
        val blkSet = addrs.map { it / bs }.toSet()
        val blks = blkSet.sorted()

        log("共 ${blks.size} 块, 有效 ${hexData.written.size}B", "ok")

        if (firstByte == 0x50) {
            // 新芯片: 0x05 准备编程
            log("[2/5] 准备编程...", "step")
            serial.write(buildPkt(0x6A, byteArrayOf(0x05, 0, 0, 0x5A, 0xA5.toByte())))
            val rp = waitPacket(500)
            if (rp == null || rp.payload[0].toInt() and 0xFF != 0x05) {
                throw IllegalStateException("准备编程失败")
            }
            log("准备编程 OK", "ok")
        }
        setProgress(15)

        // 擦除
        log("[3/5] 擦除 Flash...", "step")
        clearRxBuf()
        serial.write(buildPkt(0x6A, byteArrayOf(0x03, 0, 0, 0x5A, 0xA5.toByte())))
        val rp3 = waitPacket(3000)
        if (rp3 == null || rp3.payload[0].toInt() and 0xFF != 0x03) {
            throw IllegalStateException("擦除失败")
        }
        log("擦除 OK", "ok")
        setProgress(35)

        // 写入
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

            clearRxBuf()
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

        // 复位
        log("[5/5] 复位芯片...", "step")
        clearRxBuf()
        serial.write(buildPkt(0x6A, byteArrayOf(0x82.toByte())))
        setProgress(100)
        log("STC 烧录完成!", "ok")
    }
}
