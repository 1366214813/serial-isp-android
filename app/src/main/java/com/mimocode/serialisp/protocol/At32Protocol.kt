package com.mimocode.serialisp.protocol

import com.mimocode.serialisp.hex.HexData
import com.mimocode.serialisp.usb.SerialPortManager

class At32Protocol(serial: SerialPortManager, listener: ProtocolListener) : Stm32Protocol(serial, listener) {

    override val name = "AT32 Bootloader"

    private val at32DB = mapOf(
        0x410 to "AT32F403", 0x444 to "AT32F413", 0x450 to "AT32F421",
        0x472 to "AT32F407", 0x414 to "AT32WB55"
    )

    override suspend fun detect(syncResult: SyncResult): ChipInfo {
        log("检测 AT32 芯片...", "step")

        rxBuf = ByteArray(0)
        serial.write(buildFrame(byteArrayOf(0x00)))
        val ack = readAck(500)
        if (ack != true) throw IllegalStateException("GET 命令失败")
        val verByte = waitRawByte(200)
        for (i in 0 until verByte) waitRawByte(200)
        waitRawByte(200)

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

        val name = at32DB[pid] ?: "AT32 (0x${pid.toString(16).padStart(4, '0')})"
        log("芯片: $name | PID: 0x${pid.toString(16).padStart(4, '0')}", "ok")
        return ChipInfo(name, mapOf("pid" to pid.toString(16)))
    }
}
