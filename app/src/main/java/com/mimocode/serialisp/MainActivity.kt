package com.mimocode.serialisp

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mimocode.serialisp.databinding.ActivityMainBinding
import com.mimocode.serialisp.hex.HexData
import com.mimocode.serialisp.hex.HexParser
import com.mimocode.serialisp.protocol.*
import com.mimocode.serialisp.ui.LogAdapter
import com.mimocode.serialisp.usb.Parity
import com.mimocode.serialisp.usb.SerialPortManager
import com.mimocode.serialisp.usb.UsbDeviceManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.Intent
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var serial: SerialPortManager
    private lateinit var logAdapter: LogAdapter

    private var hexData: HexData? = null
    private var detectedChip: ChipInfo? = null
    private var isMonitorMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        serial = SerialPortManager(this)
        setupUI()
        setupLog()
        showWelcome()
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener { connectDevice() }
        binding.btnDisconnect.setOnClickListener { disconnectDevice() }
        binding.btnMonitor.setOnClickListener { toggleMonitor() }
        binding.btnDetect.setOnClickListener { detectChip() }
        binding.btnProgram.setOnClickListener { programChip() }
        binding.btnClearLog.setOnClickListener { logAdapter.clear() }
        binding.btnSelectFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            startActivityForResult(intent, 1001)
        }

        binding.swWireless.setOnCheckedChangeListener { _, _ -> }

        // 串口监视：发送按钮
        binding.btnSend.setOnClickListener { sendHex() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            data?.data?.let { loadFile(it) }
        }
    }

    private fun loadFile(uri: android.net.Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bytes = inputStream.readBytes()
            inputStream.close()
            val fileName = uri.lastPathSegment ?: "unknown"
            hexData = if (fileName.endsWith(".hex", true)) {
                HexParser.parseHex(String(bytes))
            } else {
                HexParser.parseBin(bytes)
            }
            log("载入: $fileName (${hexData?.size}B)", "ok")
            binding.tvFileInfo.text = "已载入: $fileName (${hexData?.size}B)"
            binding.tvFileInfo.visibility = View.VISIBLE
            binding.btnDetect.isEnabled = serial.isConnected
            binding.btnProgram.isEnabled = serial.isConnected && hexData != null
        } catch (e: Exception) {
            log("文件加载失败: ${e.message}", "err")
        }
    }

    private fun connectDevice() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val devices = UsbDeviceManager.findSupportedDevices(usbManager)
        if (devices.isEmpty()) {
            Toast.makeText(this, "未检测到 USB 设备", Toast.LENGTH_SHORT).show()
            return
        }
        connectToDevice(devices[0])
    }

    private fun connectToDevice(device: UsbDevice) {
        val deviceName = UsbDeviceManager.getDeviceName(device)
        log("连接: $deviceName", "step")

        UsbDeviceManager.requestPermission(this, device,
            onGranted = {
                val baud = binding.spBaud.selectedItem.toString().toInt()
                val parity = getParity()
                if (serial.connect(device, baud, parity)) {
                    log("已连接: $deviceName (${baud} ${getParityStr()})", "ok")
                    binding.tvConnStatus.text = "已连接: $deviceName"
                    binding.btnConnect.isEnabled = false
                    binding.btnDisconnect.isEnabled = true
                    binding.btnMonitor.isEnabled = true
                    binding.btnDetect.isEnabled = true
                    startReceiveListener()
                } else {
                    log("连接失败", "err")
                }
            },
            onDenied = { log("拒绝 USB 权限", "err") }
        )
    }

    private fun startReceiveListener() {
        lifecycleScope.launch {
            for (data in serial.receivedData) {
                if (isMonitorMode) {
                    val hex = data.joinToString(" ") { "%02X".format(it) }
                    log("RX: $hex", "rx")
                }
            }
        }
    }

    private fun disconnectDevice() {
        serial.disconnect()
        isMonitorMode = false
        binding.tvConnStatus.text = "未连接"
        binding.btnConnect.isEnabled = true
        binding.btnDisconnect.isEnabled = false
        binding.btnMonitor.isEnabled = false
        binding.btnMonitor.text = "📡 监视"
        binding.btnDetect.isEnabled = false
        binding.btnProgram.isEnabled = false
        log("已断开", "warn")
    }

    private fun toggleMonitor() {
        isMonitorMode = !isMonitorMode
        binding.btnMonitor.text = if (isMonitorMode) "📡 监视 ON" else "📡 监视"
        log(if (isMonitorMode) "监视开启 - 显示所有串口数据" else "监视关闭", "step")
    }

    private fun sendHex() {
        val hex = binding.etSendData.text.toString().trim()
        if (hex.isEmpty()) {
            Toast.makeText(this, "请输入HEX数据", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val bytes = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
            serial.write(bytes)
            val sent = bytes.joinToString(" ") { "%02X".format(it) }
            log("TX: $sent", "tx")
            binding.etSendData.setText("")
        } catch (e: Exception) {
            log("发送失败: ${e.message}", "err")
        }
    }

    private fun detectChip() {
        if (!serial.isConnected) { log("请先连接串口", "err"); return }
        binding.btnDetect.isEnabled = false
        binding.btnProgram.isEnabled = false

        lifecycleScope.launch {
            try {
                val protocol = StcProtocol(serial, object : ProtocolListener {
                    override fun onLog(message: String, level: String) {
                        log(message, level)
                    }
                    override fun onProgress(percent: Int) {
                        runOnUiThread { binding.progressBar.progress = percent }
                    }
                })
                log("检测 STC 芯片...", "step")
                val syncResult = protocol.sync()
                if (syncResult != null) {
                    detectedChip = protocol.detect(syncResult)
                    log("检测完成: ${detectedChip?.name}", "ok")
                    binding.btnProgram.isEnabled = hexData != null
                } else {
                    log("同步失败 - 请查看串口数据调试", "err")
                }
            } catch (e: Exception) {
                log("检测失败: ${e.message}", "err")
            }
            binding.btnDetect.isEnabled = true
        }
    }

    private fun programChip() {
        if (!serial.isConnected) { log("请先连接串口", "err"); return }
        if (hexData == null) { log("请选择固件文件", "err"); return }

        binding.btnDetect.isEnabled = false
        binding.btnProgram.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        isMonitorMode = false

        lifecycleScope.launch {
            try {
                val protocol = StcProtocol(serial, object : ProtocolListener {
                    override fun onLog(message: String, level: String) {
                        log(message, level)
                    }
                    override fun onProgress(percent: Int) {
                        runOnUiThread { binding.progressBar.progress = percent }
                    }
                })
                val syncResult = protocol.sync()
                if (syncResult != null) {
                    protocol.detect(syncResult)
                    protocol.program(hexData!!)
                } else {
                    log("同步失败", "err")
                }
            } catch (e: Exception) {
                log("烧录失败: ${e.message}", "err")
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnDetect.isEnabled = true
                binding.btnProgram.isEnabled = hexData != null
            }
        }
    }

    private fun getParity(): Parity {
        return when (binding.spLineCoding.selectedItemPosition) {
            0 -> Parity.EVEN
            1 -> Parity.NONE
            else -> Parity.EVEN
        }
    }

    private fun getParityStr(): String {
        return when (getParity()) {
            Parity.EVEN -> "8E1"
            Parity.ODD -> "8O1"
            Parity.NONE -> "8N1"
        }
    }

    private fun log(message: String, level: String = "info") {
        runOnUiThread {
            logAdapter.addEntry(message, level)
            binding.rvLog.scrollToPosition(logAdapter.itemCount - 1)
        }
    }

    private fun setupLog() {
        logAdapter = LogAdapter()
        binding.rvLog.layoutManager = LinearLayoutManager(this)
        binding.rvLog.adapter = logAdapter
    }

    private fun showWelcome() {
        listOf(
            "━━━━━━━━━━━━━━━━━━━━━━━━━━",
            "STC ISP 烧录工具 v2.0",
            "",
            "使用步骤:",
            "1. 连接串口 (2400+8N1)",
            "2. 选文件",
            "3. 点「检测芯片」时给目标板上电",
            "4. 烧录",
            "",
            "调试: 开启「监视」可查看串口数据",
            "━━━━━━━━━━━━━━━━━━━━━━━━━━"
        ).forEach { log(it, "data") }
    }

    override fun onDestroy() {
        super.onDestroy()
        serial.disconnect()
    }
}
