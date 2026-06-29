package com.mimocode.serialisp.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentLinkedQueue

enum class Parity(val value: Int) {
    NONE(0), EVEN(1), ODD(2)
}

class SerialPortManager(private val context: Context) {

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var usbInterface: UsbInterface? = null
    private var usbDevice: UsbDevice? = null
    private var readThread: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var isConnected = false
        private set

    private val rxChannel = Channel<ByteArray>(Channel.UNLIMITED)
    val receivedData: Channel<ByteArray> get() = rxChannel

    private var currentBaudRate = 115200
    private var currentParity = Parity.EVEN

    companion object {
        private const val CH34X_REQ_WRITE_REG = 0x9A
        private const val CH34X_REQ_READ_REG = 0x95
        private const val CH34X_REG_MODEM = 0xA4
        private const val CH34X_BIT_DTR = 0x20
        private const val CH34X_BIT_RTS = 0x40
    }

    fun connect(device: UsbDevice, baudRate: Int, parity: Parity): Boolean {
        try {
            val usbInterface = findCdcInterface(device)
                ?: device.getInterface(0)

            val conn = usbManager.openDevice(device) ?: return false

            if (!conn.claimInterface(usbInterface, true)) {
                conn.close()
                return false
            }

            // Find bulk endpoints
            for (i in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        inEndpoint = ep
                    } else {
                        outEndpoint = ep
                    }
                }
            }

            if (inEndpoint == null || outEndpoint == null) {
                conn.close()
                return false
            }

            connection = conn
            this.usbDevice = device
            this.usbInterface = usbInterface
            this.isConnected = true
            this.currentBaudRate = baudRate
            this.currentParity = parity

            initCh340(conn, device)
            setParameters(baudRate, parity)
            startReading(conn)

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun findCdcInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                return iface
            }
        }
        return null
    }

    private fun initCh340(conn: UsbDeviceConnection, device: UsbDevice) {
        // CH340 initialization sequence (from linux ch341 driver)
        controlOut(conn, 0x9A, 0x0706, 0x0007)
        controlOut(conn, 0x9A, 0x0706, 0x0007)
        controlOut(conn, 0x9A, 0x0706, 0x0007)
        controlOut(conn, 0x9A, 0x0706, 0x0007)
        controlOut(conn, 0x9A, 0x0706, 0x0007)
        controlOut(conn, 0x9A, 0x0706, 0x0007)
        controlOut(conn, 0x9A, 0x0706, 0x0007)
        controlOut(conn, 0x9A, 0x0706, 0x0009)
        controlOut(conn, 0x9A, 0x0706, 0x0008)
    }

    fun setParameters(baudRate: Int, parity: Parity) {
        val conn = connection ?: return
        currentBaudRate = baudRate
        currentParity = parity

        try {
            setBaudRate(conn, baudRate)
            setLineControl(conn, parity)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setBaudRate(conn: UsbDeviceConnection, baudRate: Int) {
        val divisor = getBaudRateDivisor(baudRate)
        if (divisor < 0) return

        if (divisor == 0) {
            controlOut(conn, CH34X_REQ_WRITE_REG, 0x1312, 0xD982)
            controlOut(conn, CH34X_REQ_WRITE_REG, 0x0F2C, 0x0004)
            controlOut(conn, CH34X_REQ_WRITE_REG, 0x2727, 0x0000)
        } else {
            val factor = divisor and 0xFF
            controlOut(conn, CH34X_REQ_WRITE_REG, 0x1312, 0xD982)
            controlOut(conn, CH34X_REQ_WRITE_REG, 0x0F2C, 0x0004)
            controlOut(conn, CH34X_REQ_WRITE_REG, 0x2727, 0x0000)
            controlOut(conn, CH34X_REQ_WRITE_REG, 0x1312, 0x9488 or ((factor and 0xFF) shl 8))
        }
    }

    private fun getBaudRateDivisor(baudRate: Int): Int {
        return when (baudRate) {
            50 -> 0; 75 -> 1; 100 -> 2; 150 -> 3
            300 -> 4; 600 -> 5; 1200 -> 6; 2400 -> 7
            4800 -> 8; 9600 -> 9; 19200 -> 10; 38400 -> 11
            57600 -> 12; 115200 -> 13; 230400 -> 14; 460800 -> 15
            500000 -> 16; 576000 -> 17; 921600 -> 18; 1000000 -> 19
            else -> -1
        }
    }

    private fun setLineControl(conn: UsbDeviceConnection, parity: Parity) {
        var lcr = 0x03 // 8 data bits
        when (parity) {
            Parity.EVEN -> lcr = lcr or 0x18
            Parity.ODD -> lcr = lcr or 0x08
            Parity.NONE -> {}
        }
        lcr = lcr or 0xC0
        controlOut(conn, CH34X_REQ_WRITE_REG, 0x0706, lcr)
    }

    private fun startReading(conn: UsbDeviceConnection) {
        readThread = scope.launch {
            val buffer = ByteArray(4096)
            while (isActive && isConnected) {
                val ep = inEndpoint ?: break
                val result = conn.bulkTransfer(ep, buffer, buffer.size, 100)
                if (result > 0) {
                    val data = ByteArray(result)
                    System.arraycopy(buffer, 0, data, 0, result)
                    rxChannel.send(data)
                }
            }
        }
    }

    fun write(data: ByteArray) {
        val conn = connection ?: return
        val ep = outEndpoint ?: return

        scope.launch {
            try {
                var offset = 0
                while (offset < data.size) {
                    val chunkSize = minOf(data.size - offset, ep.maxPacketSize)
                    val chunk = data.copyOfRange(offset, offset + chunkSize)
                    val result = conn.bulkTransfer(ep, chunk, chunk.size, 1000)
                    if (result < 0) break
                    offset += result
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun reconnect(baudRate: Int, parity: Parity) {
        val device = usbDevice ?: return
        val iface = usbInterface ?: return

        // 停止读线程
        readThread?.cancel()
        readThread = null

        // 关闭旧连接
        try { connection?.close() } catch (e: Exception) { e.printStackTrace() }
        connection = null
        inEndpoint = null
        outEndpoint = null

        // 重新打开
        try {
            val conn = usbManager.openDevice(device) ?: return
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                return
            }

            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEndpoint = ep
                    else outEndpoint = ep
                }
            }

            connection = conn
            currentBaudRate = baudRate
            currentParity = parity
            setParameters(baudRate, parity)
            startReading(conn)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun disconnect() {
        isConnected = false
        readThread?.cancel()
        readThread = null
        try {
            connection?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        connection = null
        inEndpoint = null
        outEndpoint = null
        scope.cancel()
    }

    private fun controlOut(conn: UsbDeviceConnection, request: Int, value: Int, index: Int): Int {
        return conn.controlTransfer(
            0x40, request, value, index, null, 0, 1000
        )
    }
}
