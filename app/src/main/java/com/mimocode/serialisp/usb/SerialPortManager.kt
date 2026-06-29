package com.mimocode.serialisp.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.*
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException

enum class Parity(val value: Int) {
    NONE(0), EVEN(1), ODD(2)
}

class SerialPortManager(private val context: Context) {

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialDriver: UsbSerialDriver? = null
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    private val rxChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var isConnected = false
        private set

    val receivedData: Channel<ByteArray> get() = rxChannel

    fun connect(device: UsbDevice, baudRate: Int, parity: Parity): Boolean {
        return try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                ?: UsbDeviceManager.getDriverForDevice(device).probeDevice(device)
                ?: return false

            val port = driver.ports[0]
            val connection = usbManager.openDevice(device) ?: return false

            port.open(connection)
            port.setParameters(baudRate, 8, parity.value, 1)

            this.serialDriver = driver
            this.port = port
            this.isConnected = true

            startReading()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun startReading() {
        ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                scope.launch { rxChannel.send(data) }
            }
            override fun onRunError(e: Exception?) {
                e?.printStackTrace()
            }
        }).also { it.start() }
    }

    fun write(data: ByteArray) {
        scope.launch {
            try {
                port?.write(data, 1000)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    fun setParameters(baudRate: Int, parity: Parity) {
        try {
            port?.setParameters(baudRate, 8, parity.value, 1)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun disconnect() {
        ioManager?.stop()
        ioManager = null
        try {
            port?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        port = null
        serialDriver = null
        isConnected = false
        scope.cancel()
    }
}


