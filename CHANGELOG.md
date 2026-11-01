# 更新日志

本项目的所有重要变更都会记录在此文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)；
版本号采用日期式命名 `YYYY.MM.DD.构建号`，与 Git 标签 `v<版本号>` 一一对应。

## [未发布]

## [2026.11.01.1] - 2026-11-01

### 新增

- 写卡和卡模拟新增“应用程序”类型，支持搜索并选择本机应用或手动输入包名，使用标准 Android 应用记录；本应用读卡时仅展示信息，不启动目标应用
- 点击标题栏打开 Fluent BottomSheet 关于面板，查看应用图标、实际版本、维护者、开源许可证和 GitHub 仓库；支持检查正式版本、查看更新说明、系统下载、进度与取消、后台恢复及安装引导
- debug 构建使用独立包名 `io.github.zyraxi21.nfc.debug`，应用名称为“NFC 通信 开发版”，版本名追加 `-debug`，可与签名正式版同时安装
- 自适应图标新增 monochrome 层（`drawable/ic_launcher_monochrome.xml`），支持 Android 13 及以上的主题图标，几何参数与前景层保持一致

### 变更

- 控件恢复 Fluent SDK 默认圆角，提示改用原生 Accent Snackbar，保留浅深色对比度、动态品牌色、菜单宽度和触控区域修正
- 输入框背景改为透明，聚焦时标签与下划线使用品牌色，下划线在聚焦或报错时加粗；简化关于标题和更新提示文案
- 应用图标改用自适应图标（adaptive icon）：背景层 `ic_launcher_background.xml` 铺满渐变，前景层 `ic_launcher_foreground.xml` 只保留白色 NFC 图形；`android:icon` / `android:roundIcon` 由 `@drawable/nfc_logo`、`@drawable/nfc_logo_round` 改指 `@mipmap/ic_launcher`、`@mipmap/ic_launcher_round`
- 前景图形按源文件 `NFC.svg` 的 18% 留白比例缩放至外沿 46dp，在 72dp 可见区内保留同等边距，圆形、圆角方形等遮罩均不会切到笔画
- Gradle Wrapper 升级至 9.8.2；通过 Daemon JVM 条件统一使用 JDK 25，并提供跨平台下载配置

### 修复

- 修复可格式化标签被当成已有 NDEF 标签而跳过实际写入的问题
- 标题栏的点击水波纹延伸至系统状态栏，修复顶部反馈范围不完整的问题
- 补齐 FluentTheme 的 `runtime-livedata` 依赖，修复新克隆项目启动闪退的问题
- Play services 依赖固定从官方 Google Maven 仓库获取，避免镜像缺少 AAR 时构建失败
- 修复桌面图标显示为圆形底衬内嵌方形图、四周露出白底的问题：此前 `android:icon` 指向普通矢量图，系统只能按 legacy 图标处理，由启动器自行补加形状底衬

## [2026.10.07.1] - 2026-10-07

首个正式版本。应用围绕 NFC 的 NDEF 数据交换，提供读卡、写卡 / 卡模拟（HCE）、端对端（P2P）三种通信模式。

### 新增

- **读卡**：通过 Reader Mode（NFC-A / NFC-B / NFC-F）前台读卡，无需整卡分发；解析文本（RTD_TEXT，自动识别 UTF-8 / UTF-16）、网址（RTD_URI，内置完整前缀表）、Wi-Fi 配置（`application/vnd.wfa.wsc`）、蓝牙 OOB 配对数据（`application/vnd.bluetooth.ep.oob`）、应用记录 AAR 及其他 MIME / 外部类型记录；支持通过 IsoDep + 自定义 AID 回退读取 HCE 模拟卡内容
- **写卡**：支持文本、网址、Wi-Fi（SSID / 密码 / 加密 / 认证可选）、蓝牙（MAC / 名称）四种数据类型；自动适配标签类型，可格式化标签先格式化再写入；提供等待卡片 → 写入中 → 成功 / 失败 的完整状态机提示
- **卡模拟（HCE）**：模拟 NFC Forum Type 4 标签，另一台手机可用读卡功能直接读回
- **端对端（P2P）**：基于 Google Nearby Connections（`P2P_CLUSTER` 策略）实现设备间广播、发现与自动连接，支持四种消息格式
- 全面采用 **Fluent 2** 设计体系并建立设计令牌层，支持深色模式与 Monet 动态取色
- 大屏自适应布局与预测性返回支持

### 变更

- 应用包名由 `com.example.myapplication` 迁移为 `io.github.zyraxi21.nfc`
- 界面实现由 Material 3 全面迁移至 Fluent 2
- 卡模拟读取协议改为带偏移量的分块读取；读卡端改用私有 AID（`F012345678`）作为主通路，NFC Forum 标准 AID 保留为回退通路
- 构建工具链现代化：AGP 9.4.1（内置 Kotlin 支持）、Gradle 9.8.0、`compileSdk` / `targetSdk` 37
- 清理未被引用的依赖，Compose 依赖统一由 BOM 约束并显式对齐至 1.7.8
- 启用 R8 代码压缩与资源裁剪，release 包体积由 30.7 MiB 降至 2.87 MiB；库自带的 137 种语言资源裁剪为中文与英文

### 修复

- 修复小米 HyperOS 设备上 HCE 会话被安全元件接管、读卡端收不到任何 APDU 的问题
- 修复 Wi-Fi WSC 记录中认证类型（`0x1003`）与加密类型（`0x100F`）的解析
- 修复深色模式下的文字可读性，并补充明暗两套对比度回归测试
- 修复 Fluent 主题启动闪退（缺少 `runtime-livedata` 依赖）
- 统一圆角尺寸，修正 Snackbar 内边距与底栏反馈范围

### 移除

- 移除早期独立的 Activity 实现（`ReadCard`、`WriteCard`、`CardEmulationDeviceActivity`、`P2PCommunication`），读写卡与卡模拟统一在 `MainActivity` + Compose 界面中完成
- 移除顶栏中的版本号显示，避免界面版本号与实际发版脱节
- 移除工程中未被引用的依赖

### 已知限制

- 仅在小米 HyperOS 3 设备上测试通过，其他机型可能不支持 HCE 卡模拟
- 小米设备需先关闭小米钱包的「默认卡 / 智能选卡 / 双击电源键刷卡」，并把「默认钱包应用」改为非钱包应用，否则 NFC 控制器会把整条 ISO-DEP 通道交给安全元件
- 模拟器不支持 NFC 读写与卡模拟，请使用真机
- 端对端需要两台真机；卡模拟互通需要收发两端安装同一版本
- Android 10 起系统已移除 NFC P2P（Android Beam），端对端功能基于 Nearby Connections 实现

更完整的说明见 [README](README.md)。

[未发布]: https://github.com/Friends-of-zyraxi/NFC-Multi-Mode-Communication/compare/v2026.11.01.1...HEAD
[2026.11.01.1]: https://github.com/Friends-of-zyraxi/NFC-Multi-Mode-Communication/compare/v2026.10.07.1...v2026.11.01.1
[2026.10.07.1]: https://github.com/Friends-of-zyraxi/NFC-Multi-Mode-Communication/releases/tag/v2026.10.07.1
