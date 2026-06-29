package com.mimocode.serialisp.protocol

import com.mimocode.serialisp.hex.HexData
import com.mimocode.serialisp.usb.SerialPortManager
import kotlinx.coroutines.delay

class EspProtocol(serial: SerialPortManager, listener: ProtocolListener) : BaseProtocol(serial, listener) {

    override val name = "ESP32 UART Boot"

    private fun slipFrame(data: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        out.add(0xC0.toByte())
        for (b in data) {
            when (b.toInt() and 0xFF) {
                0xC0 -> { out.add(0xDB.toByte()); out.add(0xDC.toByte()) }
                0xDB -> { out.add(0xDB.toByte()); out.add(0xDD.toByte()) }
                0x11 -> { out.add(0xDB.toByte()); out.add(0xDE.toByte()) }
                else -> out.add(b)
            }
        }
        out.add(0xC0.toByte())
        return out.toByteArray()
    }

    private fun slipUnframe(buf: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        var esc = false
        for (b in buf) {
            if (b.toInt() and 0xFF == 0xC0) continue
            if (esc) {
                when (b.toInt() and 0xFF) {
                    0xDC -> out.add(0xC0.toByte())
                    0xDD -> out.add(0xDB.toByte())
                    0xDE -> out.add(0x11)
                    else -> out.add(b)
                }
                esc = false
            } else if (b.toInt() and 0xFF == 0xDB) {
                esc = true
            } else {
                out.add(b)
            }
        }
        return out.toByteArray()
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (i in 0 until 8) {
                crc = if ((crc and 1) != 0) (crc shr 1) xor 0xA001 else crc shr 1
            }
        }
        return crc
    }

    private fun buildCmd(op: Int, data: ByteArray): ByteArray {
        val pkt = ByteArray(2 + 2 + data.size + 2)
        pkt[0] = op.toByte(); pkt[1] = 0
        pkt[2] = (data.size and 0xFF).toByte()
        pkt[3] = ((data.size shr 8) and 0xFF).toByte()
        data.copyInto(pkt, 4)
        val cs = crc16(pkt.copyOf(4 + data.size))
        pkt[4 + data.size] = (cs and 0xFF).toByte()
        pkt[5 + data.size] = ((cs shr 8) and 0xFF).toByte()
        return slipFrame(pkt)
    }

    private suspend fun readSlipPacket(timeoutMs: Long): ByteArray? {
        val start = System.currentTimeMillis()
        val raw = mutableListOf<Byte>()
        while (System.currentTimeMillis() - start < timeoutMs) {
            while (rxBuf.isNotEmpty()) {
                val b = rxBuf[0]
                rxBuf = if (rxBuf.size > 1) rxBuf.copyOfRange(1, rxBuf.size) else ByteArray(0)
                if (b.toInt() and 0xFF == 0xC0) {
                    if (raw.isNotEmpty()) {
                        val frame = raw.toByteArray()
                        raw.clear()
                        val unframed = slipUnframe(frame)
                        if (unframed.size >= 10) return unframed
                    }
                } else {
                    raw.add(b)
                }
            }
            delay(5)
        }
        return null
    }

    override suspend fun sync(): SyncResult? {
        log("=== ESP32 UART Boot 同步 ===", "step")
        log("ESP32 需要进入 Boot 模式: 拉低 GPIO0 后按 RST", "warn")
        log("部分开发板可自动进入 (DTR/RTS 控制)", "warn")

        for (attempt in 0 until 5) {
            rxBuf = ByteArray(0)
            val syncData = ByteArray(36)
            syncData[0] = 0x07; syncData[1] = 0x07; syncData[2] = 0x12; syncData[3] = 0x20
            for (i in 4 until 36) syncData[i] = 0x55

            serial.write(slipFrame(syncData))
            val rsp = readSlipPacket(1000)
            if (rsp != null && rsp[0].toInt() and 0xFF == 0x01) {
                log("ESP32 同步成功!", "ok")
                return SyncResult(rsp)
            }
            log("尝试 ${attempt + 1}/5 失败, 重试...", "warn")
            delay(200)
        }
        log("ESP32 同步失败", "err")
        return null
    }

    override suspend fun detect(syncResult: SyncResult): ChipInfo {
        log("已连接 ESP32 系列芯片", "ok")
        return ChipInfo("ESP32/ESP8266")
    }

    override suspend fun program(hexData: HexData) {
        val d = hexData.data
        log("固件大小: ${d.size}B", "ok")

        // Step 1: SPI Flash 参数
        log("[1/4] SPI Flash 参数...", "step")
        rxBuf = ByteArray(0)
        val spiParams = ByteArray(24)
        spiParams[0] = 0x01
        spiParams[5] = 0x10; spiParams[7] = 0x80.toByte()
        spiParams[9] = 0x40; spiParams[13] = 0x20
        spiParams[16] = 0x04
        serial.write(buildCmd(0x0B, spiParams))
        readSlipPacket(500)
        setProgress(10)

        // Step 2: Flash 开始
        log("[2/4] Flash 擦除/准备...", "step")
        val blocks = (d.size + 4095) / 4096
        val beginData = ByteArray(16)
        beginData[4] = (d.size and 0xFF).toByte()
        beginData[5] = ((d.size shr 8) and 0xFF).toByte()
        beginData[6] = ((d.size shr 16) and 0xFF).toByte()
        beginData[7] = ((d.size shr 24) and 0xFF).toByte()
        beginData[8] = 0x00; beginData[9] = 0x10
        beginData[12] = (blocks and 0xFF).toByte()
        beginData[13] = ((blocks shr 8) and 0xFF).toByte()
        serial.write(buildCmd(0x02, beginData))
        readSlipPacket(5000)
        setProgress(20)

        // Step 3: 写入数据
        log("[3/4] 写入固件...", "step")
        val chunkSize = 1024
        val totalChunks = (d.size + chunkSize - 1) / chunkSize
        for (ci in 0 until totalChunks) {
            val offset = ci * chunkSize
            val end = minOf(offset + chunkSize, d.size)
            val chunk = d.copyOfRange(offset, end)
            val writeData = ByteArray(16 + chunk.size)
            writeData[0] = 0x02
            writeData[4] = (ci and 0xFF).toByte()
            writeData[5] = ((ci shr 8) and 0xFF).toByte()
            writeData[8] = (chunk.size and 0xFF).toByte()
            writeData[9] = ((chunk.size shr 8) and 0xFF).toByte()
            var cs = 0
            for (b in chunk) cs += b.toInt() and 0xFF
            writeData[12] = (cs and 0xFF).toByte()
            writeData[13] = ((cs shr 8) and 0xFF).toByte()
            chunk.copyInto(writeData, 16)

            rxBuf = ByteArray(0)
            serial.write(buildCmd(0x03, writeData))
            val rsp = readSlipPacket(2000)
            if (rsp == null || rsp[0].toInt() and 0xFF != 0x01) {
                throw IllegalStateException("ESP32 写入失败 @chunk $ci")
            }
            if ((ci + 1) % 10 == 0 || ci == totalChunks - 1) {
                log("${(ci + 1) * 100 / totalChunks}%", "data")
            }
            setProgress(20 + (ci + 1) * 70 / totalChunks)
        }

        // Step 4: 完成
        log("[4/4] 完成...", "step")
        val endData = ByteArray(8)
        endData[0] = 0x01
        serial.write(buildCmd(0x04, endData))
        readSlipPacket(500)
        setProgress(100)
        log("ESP32 烧录完成! 请按 RST 启动", "ok")
    }
}
