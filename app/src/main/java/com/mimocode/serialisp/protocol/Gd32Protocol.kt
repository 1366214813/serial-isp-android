package com.mimocode.serialisp.protocol

import com.mimocode.serialisp.hex.HexData
import com.mimocode.serialisp.usb.SerialPortManager

class Gd32Protocol(serial: SerialPortManager, listener: ProtocolListener) : Stm32Protocol(serial, listener) {

    override val name = "GD32 Bootloader"

    private val gd32DB = mapOf(
        0x430 to "GD32F130", 0x445 to "GD32F150", 0x410 to "GD32F103",
        0x444 to "GD32E103", 0x432 to "GD32F303", 0x442 to "GD32F450"
    )

    override suspend fun detect(syncResult: SyncResult): ChipInfo {
        log("检测 GD32 芯片...", "step")

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

        val name = gd32DB[pid] ?: "GD32 (0x${pid.toString(16).padStart(4, '0')})"
        log("芯片: $name | PID: 0x${pid.toString(16).padStart(4, '0')}", "ok")
        return ChipInfo(name, mapOf("pid" to pid.toString(16)))
    }
}
