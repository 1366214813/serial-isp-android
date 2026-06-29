package com.mimocode.serialisp.protocol

import com.mimocode.serialisp.hex.HexData
import com.mimocode.serialisp.usb.SerialPortManager
import kotlinx.coroutines.delay

open class Stm32Protocol(serial: SerialPortManager, listener: ProtocolListener) : BaseProtocol(serial, listener) {

    override val name = "STM32 Bootloader"

    private val stm32DB = mapOf(
        0x410 to "STM32F103", 0x413 to "STM32F103 HD", 0x414 to "STM32F107",
        0x418 to "STM32F215", 0x419 to "STM32F217", 0x420 to "STM32F405/407",
        0x421 to "STM32F401", 0x423 to "STM32F411", 0x431 to "STM32F413",
        0x433 to "STM32F423", 0x432 to "STM32F446", 0x434 to "STM32F469",
        0x435 to "STM32F479", 0x440 to "STM32F030", 0x444 to "STM32F070",
        0x445 to "STM32F072", 0x448 to "STM32F091", 0x450 to "STM32L0",
        0x460 to "STM32L4", 0x462 to "STM32L4+", 0x470 to "STM32G0",
        0x471 to "STM32G0B", 0x472 to "STM32G0C", 0x495 to "STM32H743",
        0x497 to "STM32H753", 0x499 to "STM32H750"
    )

    protected open fun buildFrame(data: ByteArray): ByteArray {
        val out = ByteArray(data.size + 1)
        data.copyInto(out)
        var cs = 0
        for (b in data) cs = cs xor (b.toInt() and 0xFF)
        out[data.size] = (cs and 0xFF).toByte()
        return out
    }

    protected suspend fun readAck(timeoutMs: Long = 1000): Boolean? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val b = waitRawByte(50)
            if (b == 0x79) return true  // ACK
            if (b == 0x1F) return false // NACK
        }
        return null
    }

    override suspend fun sync(): SyncResult? {
        log("=== STM32 UART Bootloader 同步 ===", "step")
        log("STM32 需要进入 Boot 模式: BOOT0=HIGH, 按 RST", "warn")

        for (attempt in 0 until 10) {
            rxBuf = ByteArray(0)
            serial.write(byteArrayOf(0x7F))
            val rsp = readAck(1000)
            if (rsp == true) {
                log("STM32 同步成功!", "ok")
                return SyncResult()
            }
            if (rsp == false) log("收到 NACK, 重试...", "warn")
        }
        log("STM32 同步失败", "err")
        return null
    }

    override suspend fun detect(syncResult: SyncResult): ChipInfo {
        log("获取芯片信息...", "step")

        // GET
        rxBuf = ByteArray(0)
        serial.write(buildFrame(byteArrayOf(0x00)))
        val ack = readAck(500)
        if (ack != true) throw IllegalStateException("GET 命令失败")

        val verByte = waitRawByte(200)
        for (i in 0 until verByte) waitRawByte(200)
        waitRawByte(200)
        log("Bootloader v${verByte / 10.0}", "data")

        // GET_ID
        rxBuf = ByteArray(0)
        serial.write(buildFrame(byteArrayOf(0x02)))
        val ack2 = readAck(500)
        if (ack2 != true) throw IllegalStateException("GET_ID 失败")

        val pidLen = waitRawByte(200) + 1
        var pid = 0
        for (i in 0 until pidLen) {
            pid = (pid shl 8) or waitRawByte(200)
        }
        waitRawByte(200)

        val name = stm32DB[pid] ?: "未知 (0x${pid.toString(16).padStart(4, '0')})"
        log("芯片: $name | PID: 0x${pid.toString(16).padStart(4, '0')}", "ok")
        return ChipInfo(name, mapOf("pid" to pid.toString(16)))
    }

    protected open suspend fun erase() {
        log("擦除 Flash...", "step")
        rxBuf = ByteArray(0)
        serial.write(buildFrame(byteArrayOf(0x43.toByte(), 0xFF.toByte())))
        val ack = readAck(5000)
        if (ack == true) {
            log("全片擦除 OK", "ok")
            return
        }

        // Fallback: sector erase
        rxBuf = ByteArray(0)
        serial.write(buildFrame(byteArrayOf(0x44.toByte(), 0xFF.toByte(), 0xFF.toByte())))
        val ack2 = readAck(500)
        if (ack2 != true) throw IllegalStateException("擦除命令失败")

        rxBuf = ByteArray(0)
        serial.write(buildFrame(byteArrayOf(0x00)))
        val ack3 = readAck(10000)
        if (ack3 != true) throw IllegalStateException("扇区擦除失败")
        log("擦除 OK", "ok")
    }

    protected open suspend fun writeMemory(addr: Int, data: ByteArray) {
        // WRITE_MEMORY
        rxBuf = ByteArray(0)
        serial.write(buildFrame(byteArrayOf(
            0x31,
            ((addr shr 24) and 0xFF).toByte(),
            ((addr shr 16) and 0xFF).toByte(),
            ((addr shr 8) and 0xFF).toByte(),
            (addr and 0xFF).toByte()
        )))
        val ack = readAck(500)
        if (ack != true) throw IllegalStateException("WRITE_MEMORY 地址失败")

        // Data + checksum
        rxBuf = ByteArray(0)
        val padded = (data.size + 3) and 3.inv()
        val finalPkt = ByteArray(2 + padded + 1)
        finalPkt[0] = (data.size - 1).toByte()
        data.copyInto(finalPkt, 1)
        for (i in data.size until padded) finalPkt[1 + i] = 0xFF.toByte()
        var checksum = 0
        for (i in 0 until padded) checksum = checksum xor (finalPkt[1 + i].toInt() and 0xFF)
        finalPkt[1 + padded] = (checksum and 0xFF).toByte()

        serial.write(buildFrame(finalPkt))
        val ack2 = readAck(500)
        if (ack2 != true) throw IllegalStateException("WRITE_MEMORY 数据失败")
    }

    override suspend fun program(hexData: HexData) {
        val d = hexData.data
        log("固件大小: ${d.size}B", "ok")

        // 擦除
        log("[1/3] 擦除...", "step")
        erase()
        setProgress(20)

        // 写入
        log("[2/3] 写入代码...", "step")
        val chunkSize = 256
        val totalChunks = (d.size + chunkSize - 1) / chunkSize
        for (ci in 0 until totalChunks) {
            val offset = ci * chunkSize
            val end = minOf(offset + chunkSize, d.size)
            val chunk = d.copyOfRange(offset, end)
            writeMemory(offset, chunk)
            if ((ci + 1) % 10 == 0 || ci == totalChunks - 1) {
                log("${(ci + 1) * 100 / totalChunks}%", "data")
            }
            setProgress(20 + (ci + 1) * 70 / totalChunks)
        }

        // 复位
        log("[3/3] 复位...", "step")
        rxBuf = ByteArray(0)
        serial.write(buildFrame(byteArrayOf(0x21, 0x08, 0, 0, 0)))
        readAck(500)
        setProgress(100)
        log("STM32 烧录完成!", "ok")
    }
}
