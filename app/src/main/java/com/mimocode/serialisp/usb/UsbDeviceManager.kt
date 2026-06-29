package com.mimocode.serialisp.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialProber

object UsbDeviceManager {

    private const val ACTION_USB_PERMISSION = "com.mimocode.serialisp.USB_PERMISSION"

    private val CH340_VIDS = intArrayOf(0x1A86)
    private val CP210X_VIDS = intArrayOf(0x10C4)
    private val PL2303_VIDS = intArrayOf(0x067B)
    private val FTDI_VIDS = intArrayOf(0x0403)
    private val STC_VIDS = intArrayOf(0x34BF)

    fun getDriverForDevice(@Suppress("UNUSED_PARAMETER") device: UsbDevice): UsbSerialProber {
        return UsbSerialProber.getDefaultProber()
    }

    fun getDeviceName(device: UsbDevice): String {
        return when {
            CH340_VIDS.contains(device.vendorId) -> "CH340/CH341"
            CP210X_VIDS.contains(device.vendorId) -> "CP2102/CP2104"
            PL2303_VIDS.contains(device.vendorId) -> "PL2303"
            FTDI_VIDS.contains(device.vendorId) -> "FTDI FT232"
            STC_VIDS.contains(device.vendorId) -> "STC-Link"
            else -> "USB (VID:${String.format("%04X", device.vendorId)})"
        }
    }

    fun findSupportedDevices(usbManager: UsbManager): List<UsbDevice> {
        return usbManager.deviceList.values.filter { device ->
            device.vendorId != 0
        }
    }

    fun requestPermission(
        context: Context,
        device: UsbDevice,
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        if (usbManager.hasPermission(device)) {
            onGranted()
            return
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) onGranted() else onDenied()
                    ctx.unregisterReceiver(this)
                }
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        usbManager.requestPermission(device, permissionIntent)
    }
}
