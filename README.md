# NFC 通信（NFC Multi-Mode Communication）

> 基于 NFC 的多模式通信实践开发 —— 一个覆盖 **读卡、写卡、卡模拟（HCE）与端对端（P2P）通信** 的 Android 示例应用。

本项目围绕 NFC 的 NDEF 数据交换展开，实现三种通信模式：

- **读卡**：通过 Reader Mode 读取 NFC 标签中的 NDEF 数据并解析展示
- **写卡 / 卡模拟**：向标签写入文本 / 网址 / Wi-Fi / 蓝牙配置，或通过 HCE 将手机模拟成 NFC 标签供读卡器读取
- **端对端**：基于 Google Nearby Connections 实现两台设备之间的点对点消息传输

---

## 功能特性

### 1. 读卡

- 使用 Reader Mode（NFC-A / NFC-B / NFC-F）前台读卡，无需整卡分发
- 解析并展示多种 NDEF 记录：
  - **文本**（RTD_TEXT，自动识别 UTF-8 / UTF-16）
  - **URI**（RTD_URI，内置完整 URI 前缀表映射）
  - **Wi-Fi 配置**（`application/vnd.wfa.wsc`，解析 SSID / 密码 / 加密类型 / 认证类型）
  - **蓝牙 OOB 配对数据**（`application/vnd.bluetooth.ep.oob`，解析 MAC 地址与设备名）
  - **应用记录 AAR**（`android.com:pkg`）及其他 MIME / 外部类型记录
- 支持通过 IsoDep + 自定义 AID 回退读取 HCE 模拟卡内容

### 2. 写卡 / 卡模拟

- 写卡支持 4 种数据类型：**文本、网址、Wi-Fi**（SSID / 密码 / 加密 / 认证可选）、**蓝牙**（MAC / 名称）
- 自动适配标签类型：NDEF 标签直接写入；可格式化（NDEF Formatable）标签先格式化再写入
- 完整的写入状态机弹窗：等待卡片 → 写入中 → 成功 / 失败
- **卡模拟（HCE）**：模拟 NFC Forum Type 4 标签（NDEF Tag），另一台手机可用读卡功能直接读回
- 与自家读卡端之间走**私有 AID 直连**（分块传输），启动模拟时自动将本应用设为首选 HCE 服务

### 3. 端对端通信（P2P）

- 基于 **Google Nearby Connections**（`P2P_CLUSTER` 策略）：一台设备广播、另一台发现并自动发起连接
- 支持文本 / 网址 / Wi-Fi / 蓝牙四种消息格式（`TEXT:` / `URL:` / `WIFI:` / `BT:` 前缀协议）
- 完整连接状态机：断开 → 广播 / 发现 → 连接中 → 已连接 → 收发消息
- 收到消息后自动解析并以友好格式展示

---



## 环境要求

- Android Studio（推荐最新稳定版）+ JDK 17 及以上
- Android SDK Platform 37
- 真机要求：Android 14（API 34）及以上；Nearby P2P 需要支持蓝牙 / Wi-Fi
  > 模拟器不支持 NFC 读写与卡模拟，请务必使用真机调试；NFC 读写与卡模拟仍需支持 NFC
- 测试端对端需要**两台真机**；测试卡模拟需要另一台支持 NFC 的手机充当读卡器

## 使用说明

### 读卡

1. 打开应用进入「读卡」页，点击「检查 NFC 状态」（未开启时会引导跳转系统设置）
2. 将 NFC 标签（或另一台处于卡模拟状态的手机）靠近本机背面
3. 页面显示标签类型与解析后的内容

### 写卡

1. 进入「写卡/卡模拟」页，选择数据类型并填写内容
2. 点击「写入标签」，按提示将标签靠近手机背面
3. 弹窗提示写入成功或失败（含失败原因，如标签不可写、容量不足）

### 卡模拟

1. 在「写卡/卡模拟」页填写要模拟的内容，点击「卡模拟」
2. 将手机靠近 NFC 读卡器（或另一台开启读卡功能的手机）
3. 点击「停止模拟」结束卡模拟

> HCE 服务注册在 `other` 类别（不参与「触碰付款」）。互通需要**收发两端都安装本应用**。
>
> ⚠️ **小米 HyperOS 设备必须先关闭小米钱包的刷卡接管**：关闭「默认卡 / 智能选卡 / 双击电源键刷卡」，并把「默认钱包应用」改为非钱包应用。否则 NFC 控制器会把整条 ISO-DEP 通道连同兜底路由交给安全元件，本应用收不到任何 APDU。详见文末「已知限制与注意事项」。
>
> **注意：** 本应用仅在小米 HyperOS 3 设备上测试通过，其他机型可能不支持 HCE 服务。

### 端对端

1. 进入「端对端」页，首次使用需授予附近设备权限；Android 17 及以上可能还会请求本地网络权限
2. 确保手机已开启蓝牙或 Wi-Fi（Nearby Connections 自 2026 年底起不再自动开启无线装置），未开启时页面会给出引导按钮
3. 设备 A 点击「用户 2」（广播），设备 B 点击「用户 1」（发现），两台设备靠近
4. 连接成功后选择消息类型、填写内容并发送；对方页面会即时显示解析后的消息

---

## NDEF 数据格式支持

| 数据类型 | 记录类型 | 说明 |
| --- | --- | --- |
| 文本 | Well Known `T`（RTD_TEXT） | 状态字节含语言代码长度与编码（UTF-8 / UTF-16） |
| 网址 | Well Known `U`（RTD_URI） | 支持 0x00–0x23 完整前缀表 |
| Wi-Fi | MIME `application/vnd.wfa.wsc` | WSC TLV 结构：SSID（0x1045）、Network Key（0x1027）、认证类型（0x1003）、加密类型（0x100F） |
| 蓝牙 | MIME `application/vnd.bluetooth.ep.oob` | 2 字节头 + 倒序 6 字节 MAC + 可选设备名 |
| 应用记录 | External `android.com:pkg` | AAR（Android Application Record） |

---

## 卡模拟（HCE）协议

- 服务：`MyHostApduService`（继承 `HostApduService`）
- 注册的 AID（`res/xml/apduservice.xml`，类别 `other`）：
  - `F012345678` —— 私有 AID，**与自家读卡端通信的主通路**：读卡端首个 `SELECT` 用的就是它，因为它在控制器路由表中稳定指向 host
  - `D2760000850101` —— NFC Forum Type 4 标签标准 AID，作为**回退通路**保留。注意部分机型的路由表里它存在一条**指向 SE 的同名条目且排序在前**，若用它发起会话会被 SE 抢答并锁死整条 ISO-DEP 会话
- 模拟文件结构：CC 文件 `E103`（Capability Container）、NDEF 文件 `E104`（含 2 字节 NLEN 长度域，只读模拟，Write Access = Never）

支持的 APDU 命令：

| 命令 | 指令头 | 说明 |
| --- | --- | --- |
| SELECT | `0xA4` | 按 AID（P1=04）或文件 ID（P2=0C）选择 |
| READ_BINARY | `0xB0` | 按 P1P2 偏移读取 CC / NDEF 文件 |
| GET_DEVICE_NAME | `0x80010000` | 返回设备名 |
| CONFIRM_CONNECTION | `0x80020000` | 返回 `CONN_ACK` |
| READ_EMULATED_DATA | `0x80030000` | P1P2 为读取偏移量，单次最多返回 252 字节，读卡端循环取完 |

状态字（SW1SW2）：`9000` 成功 / `6D00` 指令不支持 / `6984` 数据无效 / `6A82` 文件未找到 / `6B00` 偏移越界。

---

## 权限说明

| 权限 | 用途 | 授予方式 |
| --- | --- | --- |
| `android.permission.NFC` | 读写 NFC 标签、HCE 卡模拟 | 安装时 |
| `BLUETOOTH_SCAN` | Nearby 发现附近设备 | 运行时 |
| `BLUETOOTH_ADVERTISE` | Nearby 广播本机 | 运行时 |
| `BLUETOOTH_CONNECT` | Nearby 建立连接 | 运行时 |
| `NEARBY_WIFI_DEVICES` | Nearby 连接附近 Wi-Fi 设备 | 运行时 |
| `ACCESS_LOCAL_NETWORK` | Android 17 本地网络访问 | 运行时（API 37+） |
| `ACCESS_WIFI_STATE` | 读取 Wi-Fi 无线装置状态 | 安装时 |

硬件特性声明：NFC 与 HCE 为**可选**（`required="false"`），蓝牙 BLE 为**必需**（`required="true"`）。


---

## 已知限制与注意事项

- **NFC P2P（Android Beam）自 Android 10 起被系统移除**，因此端对端功能改用 Google Nearby Connections（蓝牙 / Wi-Fi 直连）实现。
- Nearby Connections 从 2026 年底起不再自动开启 Wi-Fi / 蓝牙；应用会在启动广播或发现前检查无线装置，并在关闭时引导用户手动开启。
- 早期的独立 Activity 实现（`ReadCard.kt`、`WriteCard.kt`、`CardEmulationDeviceActivity`、`P2PCommunication.kt`）已全部清理，读写卡与卡模拟统一在 `MainActivity` + Compose 界面中完成。
- **小米钱包的「默认卡 / 智能选卡 / 双击电源键刷卡」会接管 NFC 控制器的整条 ISO-DEP 通道。** 开启时 `dumpsys nfc` 会显示 `mEnableHostRouting: false`、`Default route: secure element`，且 `Empty_AID` 指向 SE——此时任何**没有显式 host 条目**的 AID 都会被送进安全元件，本应用的 HCE 服务收不到任何 APDU（读卡器读到的是系统「碰一碰」的 `com.xiaomi.mi_connect_service:tap_top`，或安全元件返回的 `6A82`）。由于 **AID 路由在会话的第一个 `SELECT AID` 时即绑定**，首个 SELECT 一旦落到 SE，后续所有 APDU（包括平台自己的 NDEF 检查）都进不了 host，表现为完全读不到内容。
- **上述问题的修复**：关闭「默认卡 / 智能选卡 / 双击电源键刷卡」，并把「默认钱包应用」改为非钱包应用，然后重启一次 NFC。**判据是 `Default route: host` 与 `Empty_AID → 0x00`**（`mEnableHostRouting` 并非决定性字段，实测有设备在问题解决后它仍显示 `false`）。诊断命令：`adb shell dumpsys nfc | Select-String "Default route|Empty_AID|AID_F012345678|AID_D2760000850101"`。
- Wi-Fi WSC 记录（`application/vnd.wfa.wsc`）的**写入与解析均遵循 Wi-Fi Simple Configuration 规范**：属性 `0x1003` = Authentication Type、`0x100F` = Encryption Type、`0x1027` = Network Key、`0x1045` = SSID，取值表同样按规范实现，可与标准第三方读写器互操作。
- Wi-Fi 密码、蓝牙 MAC 等数据以标准（未加密）格式存储在标签中，请勿在公共标签中写入敏感信息。
- 卡模拟内容为只读模拟（Write Access = Never），读卡方无法修改。
- 卡模拟读取协议自 2026-09 版起改为带偏移量的分块读取，**收发两端需安装同一版本**，跨版本可能取到错位数据。
- 应用声明 BLE 为必需硬件，且 minSdk 为 34，仅支持 Android 14 及以上、带蓝牙 BLE 的设备。
- 读卡 / 写卡 / 卡模拟均需真机与实体标签测试，模拟器与不支持 NFC 的设备无法使用相关功能。

---

## 更新日志

各版本的功能变更与修复记录见 [CHANGELOG.md](CHANGELOG.md)。

---

## 许可证

本项目以 **GNU General Public License v3.0**（GPL-3.0）发布，完整条款见根目录的 [LICENSE](LICENSE)。

你可以自由使用、修改和再分发本项目，但**衍生作品必须同样以 GPLv3 开放源代码**，并保留原有的版权声明。

### 第三方依赖

构建时引用的第三方组件及其许可证：

| 依赖 | 许可证 |
| --- | --- |
| AndroidX（core-ktx、appcompat、lifecycle、navigation、activity-compose 等） | Apache-2.0 |
| Jetpack Compose（ui、material3、ui-tooling） | Apache-2.0 |
| Material Components for Android | Apache-2.0 |
| ConstraintLayout / ConstraintLayout Compose | Apache-2.0 |
| Kotlin 标准库 | Apache-2.0 |
| Google Play services — Nearby Connections | 闭源专有，受 [Google APIs 服务条款](https://developers.google.com/terms) 约束 |

除 Nearby Connections 外的组件均为 Apache-2.0，与 GPLv3 兼容。Nearby Connections 属于闭源专有组件，不在本项目源码的许可范围内，其使用需另行遵守 Google 的条款。
