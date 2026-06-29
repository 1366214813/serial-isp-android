# Multi-ISP 烧录工具 (Android)

基于 Android USB Serial API 的多芯片 ISP 烧录工具。

## 支持芯片

| 芯片 | 协议 |
|------|------|
| STC89/12/15/8/32 | STC ISP |
| ESP32/ESP8266 | UART Bootloader |
| STM32F0-H7 | STM32 Bootloader |
| GD32F1/E5 | GD32 Bootloader |
| AT32F4/34 | AT32 Bootloader |
| CH559 | CH559 Bootloader |

## 支持的 USB 转串口芯片

- CH340/CH341
- CP2102/CP2104
- PL2303
- FTDI FT232

## 编译方法

### 方法 1: Android Studio
1. 用 Android Studio 打开项目目录
2. 等待 Gradle 同步完成
3. 点击 Run 编译安装

### 方法 2: GitHub Actions
1. Fork 或推送到 GitHub
2. 在 Actions 页面运行 Build workflow
3. 下载生成的 APK

### 方法 3: 命令行
```bash
./gradlew assembleDebug
```

## Android 要求

- 最低 Android 7.0 (API 24)
- 需要支持 USB OTG

## 使用流程

1. 连接 USB 转串口适配器
2. 选择芯片系列
3. 选择 HEX/BIN 文件
4. 点击检测芯片
5. 点击烧录
