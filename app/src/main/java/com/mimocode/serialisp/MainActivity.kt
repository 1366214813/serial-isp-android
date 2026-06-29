package com.mimocode.serialisp

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var serial: SerialPortManager
    private lateinit var logAdapter: LogAdapter

    private var selectedChip: ChipType = ChipType.STC
    private var currentProtocol: BaseProtocol? = null
    private var hexData: HexData? = null
    private var detectedChip: ChipInfo? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadFile(it) }
    }

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
        // 连接按钮
        binding.btnConnect.setOnClickListener { connectDevice() }
        binding.btnDisconnect.setOnClickListener { disconnectDevice() }

        // 芯片选择
        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedChip = when {
                checkedIds.contains(R.id.chipSTC) -> ChipType.STC
                checkedIds.contains(R.id.chipESP) -> ChipType.ESP
                checkedIds.contains(R.id.chipSTM32) -> ChipType.STM32
                checkedIds.contains(R.id.chipGD32) -> ChipType.GD32
                checkedIds.contains(R.id.chipAT32) -> ChipType.AT32
                checkedIds.contains(R.id.chipCH559) -> ChipType.CH559
                else -> ChipType.STC
            }
            log("已选择: ${selectedChip.displayName}", "step")
        }

        // 文件选择
        binding.btnSelectFile.setOnClickListener {
            filePicker.launch(arrayOf("application/octet-stream", "*/*"))
        }

        // 检测和烧录
        binding.btnDetect.setOnClickListener { detectChip() }
        binding.btnProgram.setOnClickListener { programChip() }

        // 清空日志
        binding.btnClearLog.setOnClickListener { logAdapter.clear() }

        // 波特率变化
        binding.spBaud.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (serial.isConnected) {
                    val baud = parent?.getItemAtPosition(position).toString().toInt()
                    serial.setParameters(baud, getParity())
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupLog() {
        logAdapter = LogAdapter()
        binding.rvLog.layoutManager = LinearLayoutManager(this)
        binding.rvLog.adapter = logAdapter
    }

    private fun showWelcome() {
        val tips = listOf(
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
            "Multi-ISP 烧录工具 v1.0 (Android)",
            "",
            "支持芯片:",
            "· STC89/12/15/8/32 系列",
            "· ESP32/ESP8266 系列",
            "· STM32F0/H7 系列",
            "· GD32F1/E5 系列",
            "· AT32F4/34 系列",
            "· CH559",
            "",
            "流程: 连接串口 → 选芯片 → 选文件 → 检测 → 烧录",
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        )
        tips.forEach { log(it, "data") }
    }

    private fun connectDevice() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val devices = UsbDeviceManager.findSupportedDevices(usbManager)

        if (devices.isEmpty()) {
            Toast.makeText(this, "未检测到 USB 串口设备", Toast.LENGTH_SHORT).show()
            return
        }

        if (devices.size == 1) {
            connectToDevice(devices[0])
        } else {
            // TODO: Show device picker dialog
            Toast.makeText(this, "检测到 ${devices.size} 个设备，使用第一个", Toast.LENGTH_SHORT).show()
            connectToDevice(devices[0])
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        val deviceName = UsbDeviceManager.getDeviceName(device)
        log("尝试连接: $deviceName", "step")

        UsbDeviceManager.requestPermission(this, device,
            onGranted = {
                val baud = binding.spBaud.selectedItem.toString().toInt()
                val parity = getParity()

                if (serial.connect(device, baud, parity)) {
                    log("已连接: $deviceName (${baud} ${getParityStr()})", "ok")
                    binding.tvConnStatus.text = "已连接: $deviceName"
                    binding.btnConnect.isEnabled = false
                    binding.btnDisconnect.isEnabled = true
                    binding.btnDetect.isEnabled = true

                    // 启动接收数据监听
                    startReceiveListener()
                } else {
                    log("连接失败: $deviceName", "err")
                }
            },
            onDenied = {
                log("用户拒绝 USB 权限", "err")
            }
        )
    }

    private fun startReceiveListener() {
        lifecycleScope.launch {
            for (data in serial.receivedData) {
                currentProtocol?.onReceiveData(data)
            }
        }
    }

    private fun disconnectDevice() {
        serial.disconnect()
        currentProtocol = null
        detectedChip = null

        binding.tvConnStatus.text = "未连接"
        binding.btnConnect.isEnabled = true
        binding.btnDisconnect.isEnabled = false
        binding.btnDetect.isEnabled = false
        binding.btnProgram.isEnabled = false

        log("已断开", "warn")
    }

    private fun loadFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bytes = inputStream.readBytes()
            inputStream.close()

            val fileName = uri.lastPathSegment ?: "unknown"

            hexData = if (fileName.endsWith(".hex", true)) {
                val text = String(bytes)
                HexParser.parseHex(text)
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

    private fun createProtocolListener(): ProtocolListener {
        return object : ProtocolListener {
            override fun onLog(message: String, level: String) {
                lifecycleScope.launch {
                    log(message, level)
                }
            }
            override fun onProgress(percent: Int) {
                lifecycleScope.launch {
                    binding.progressBar.progress = percent
                }
            }
        }
    }

    private fun log(message: String, level: String = "info") {
        runOnUiThread {
            logAdapter.addEntry(message, level)
            binding.rvLog.scrollToPosition(logAdapter.itemCount - 1)
        }
    }

    private fun detectChip() {
        if (!serial.isConnected) {
            log("请先连接串口", "err")
            return
        }

        binding.btnDetect.isEnabled = false
        binding.btnProgram.isEnabled = false

        lifecycleScope.launch {
            try {
                val protocol = ProtocolManager.createProtocol(selectedChip, serial, createProtocolListener())
                currentProtocol = protocol

                log("检测 ${protocol.name}...", "step")
                val syncResult = protocol.sync()
                if (syncResult != null) {
                    detectedChip = protocol.detect(syncResult)
                    log("检测完成: ${detectedChip?.name}", "ok")
                    binding.btnDetect.isEnabled = true
                    binding.btnProgram.isEnabled = hexData != null
                } else {
                    log("同步失败", "err")
                    binding.btnDetect.isEnabled = true
                }
            } catch (e: Exception) {
                log("检测失败: ${e.message}", "err")
                binding.btnDetect.isEnabled = true
            }
        }
    }

    private fun programChip() {
        if (!serial.isConnected) {
            log("请先连接串口", "err")
            return
        }
        if (hexData == null) {
            log("请选择固件文件", "err")
            return
        }

        binding.btnDetect.isEnabled = false
        binding.btnProgram.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0

        lifecycleScope.launch {
            try {
                val protocol = currentProtocol ?: ProtocolManager.createProtocol(selectedChip, serial, createProtocolListener())
                currentProtocol = protocol

                if (selectedChip == ChipType.STC || selectedChip == ChipType.CH559) {
                    log("开始烧录 [${protocol.name}]...", "step")
                    val syncResult = protocol.sync()
                    if (syncResult != null) {
                        protocol.detect(syncResult)
                        protocol.program(hexData!!)
                    } else {
                        throw IllegalStateException("同步失败")
                    }
                } else if (selectedChip == ChipType.ESP) {
                    log("开始烧录 [${protocol.name}]...", "step")
                    protocol.sync()
                    protocol.program(hexData!!)
                } else {
                    log("开始烧录 [${protocol.name}]...", "step")
                    protocol.program(hexData!!)
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
        val text = binding.spLineCoding.selectedItem.toString()
        return when {
            text.contains("8E1") -> Parity.EVEN
            text.contains("8O1") -> Parity.ODD
            else -> Parity.NONE
        }
    }

    private fun getParityStr(): String {
        return when (getParity()) {
            Parity.EVEN -> "8E1"
            Parity.ODD -> "8O1"
            Parity.NONE -> "8N1"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serial.disconnect()
    }
}
