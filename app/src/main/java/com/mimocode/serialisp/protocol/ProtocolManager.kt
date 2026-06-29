package com.mimocode.serialisp.protocol

import com.mimocode.serialisp.usb.SerialPortManager

enum class ChipType(val displayName: String) {
    STC("STC89/12/15/8/32"),
    ESP("ESP32/ESP8266"),
    STM32("STM32F0-H7"),
    GD32("GD32F1/E5"),
    AT32("AT32F4/34"),
    CH559("CH559")
}

object ProtocolManager {

    fun createProtocol(
        type: ChipType,
        serial: SerialPortManager,
        listener: ProtocolListener
    ): BaseProtocol {
        return when (type) {
            ChipType.STC -> StcProtocol(serial, listener)
            ChipType.ESP -> EspProtocol(serial, listener)
            ChipType.STM32 -> Stm32Protocol(serial, listener)
            ChipType.GD32 -> Gd32Protocol(serial, listener)
            ChipType.AT32 -> At32Protocol(serial, listener)
            ChipType.CH559 -> Ch559Protocol(serial, listener)
        }
    }
}
