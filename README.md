# NFC 通信（NFC Multi-Mode Communication）

> 基于 NFC 的多模式通信实践开发 —— 一个覆盖 **读卡、写卡、卡模拟（HCE）与端对端（P2P）通信** 的 Android 示例应用。

本项目围绕 NFC 与 Nearby Connections 提供以下功能：

- **读卡**：通过 Reader Mode 读取 NFC 标签中的 NDEF 数据并解析展示
- **写卡 / 卡模拟**：向标签写入文本 / 网址 / Wi-Fi / 蓝牙配置 / 应用程序记录，或通过 HCE 将手机模拟成 NFC 标签供读卡器读取
- **端对端**：基于 Google Nearby Connections 实现两台设备之间的点对点消息传输
- **屏幕共享**：端对端连接后直接进入，或通过 NFC 卡模拟邀请对方；通过 Nearby Connections 传输实时画面，支持选择投屏方向及横竖屏查看

---

## 功能特性

- 点击标题栏打开 Fluent BottomSheet 关于面板，查看应用图标、实际版本、维护者、开源许可证和 GitHub 仓库。
- 正式版的关于面板支持检查 GitHub 正式版本、查看更新说明，以及通过系统下载器下载 APK；提供下载进度、取消、后台恢复和安装引导。
- 应用内更新仅在正式版提供；开发版隐藏更新操作，不检查或恢复下载任务，也不声明安装更新权限，需手动安装新的开发版。
- 控件采用 Fluent 默认圆角，提示使用原生 Accent Snackbar；标题栏点击水波纹覆盖顶部系统状态栏。

### 1. 读卡

- 使用 Reader Mode（NFC-A / NFC-B / NFC-F）前台读卡，无需整卡分发
- 解析并展示多种 NDEF 记录：
  - **文本**（RTD_TEXT，自动识别 UTF-8 / UTF-16）
  - **URI**（RTD_URI / 绝对 URI，由 Android NDEF API 解码）
  - **Wi-Fi 配置**（`application/vnd.wfa.wsc`，解析 SSID / 密码 / 加密类型 / 认证类型）
  - **蓝牙 OOB 配对数据**（`application/vnd.bluetooth.ep.oob`，解析 MAC 地址与设备名）
  - **应用记录 AAR**（`android.com:pkg`）及其他 MIME / 外部类型记录
- 支持通过 IsoDep + 自定义 AID 回退读取 HCE 模拟卡内容
- 每条记录独立显示，配置内容按字段排列；正文支持选择文字、展开长内容、单条复制和全部复制
- 网址、应用、Wi-Fi 和蓝牙记录配有手动操作按钮；扫描本身只展示信息。Wi-Fi 经系统确认添加到已保存网络，再由用户在系统页面确认连接；蓝牙按 MAC 地址发起系统配对，已配对设备进入蓝牙设置，实际连接取决于外设服务
- 标签 UID、通信技术、NDEF 容量、写入状态及原始载荷默认收起；原始载荷预览前 256 B，可复制完整数据
- 展开标签信息可查看系统提供的标签类型、ATQA、SAK、芯片系列、物理容量及扇区布局、NDEF 存储格式与系统只读设置能力；不同技术还会显示 ISO-DEP、NFC-B、NFC-F 或 NFC-V 的发现参数。芯片系列按标签特征识别，缺失参数直接省略；物理容量与 NDEF 可用容量分别展示，未读到 NDEF 时仍可查看标签信息

### 2. 写卡 / 卡模拟

- 写卡和卡模拟支持 5 种数据类型：**文本、网址、Wi-Fi**（SSID / 密码 / 加密 / 认证可选）、**蓝牙**（MAC / 名称）、**应用程序**（Android 包名）
- 同一列表可混合添加多条记录，支持独立编辑、删除、上移和下移；写卡和卡模拟均按列表顺序生成一条完整 NDEF 消息
- 显示记录数和编码后的总字节数；任何一条记录无效时禁止提交，写入前检查标签实际容量，卡模拟最多支持 65,532 B 的 NDEF 消息
- 编辑列表与待写入的完整消息可在 Activity 重建后恢复，取消操作后仍可继续编辑列表
- 应用程序可从本机具有启动入口的应用列表中选择，支持按名称或包名搜索，也可手动填写包名（允许填写本机尚未安装的目标应用）
- 应用程序使用标准 AAR（`android.com:pkg`）记录：退出本应用后扫描标签，由 Android 系统打开目标应用；在本应用读卡时先展示包名，由用户点击按钮打开
- 网址使用标准 URI 记录；Wi-Fi 使用标准 WSC Configuration Token（开放、WPA-PSK、WPA2-PSK），蓝牙使用标准 BR/EDR OOB 配对记录。支持这些记录的扫描设备由系统处理打开网址、连接 Wi-Fi 或蓝牙配对，必要时由用户确认；本应用内扫描先展示信息，外部操作需点击对应按钮
- 自动适配标签类型：NDEF 标签直接写入；可格式化（NDEF Formatable）标签先格式化再写入
- 完整的写入状态机弹窗：等待卡片 → 写入中 → 成功 / 失败
- **卡模拟（HCE）**：模拟 NFC Forum Type 4 标签（NDEF Tag），另一台手机可用读卡功能直接读回
- 与自家读卡端之间走**私有 AID 直连**（分块传输），启动模拟时自动将本应用设为首选 HCE 服务

### 3. 端对端通信（P2P）

- 基于 **Google Nearby Connections**（`P2P_POINT_TO_POINT` 策略）：一台设备广播、另一台发现并自动发起连接，适合当前的一对一记录通信与视频传输
- 与读写卡共用文本、网址、Wi-Fi、蓝牙和应用程序五种类型，支持混合多记录编辑、删除、排序及本机应用选择；统一传输标准 NDEF 二进制消息，SSID、密码和正文中的分隔符及换行会完整保留
- 完整连接状态机：断开 → 广播 / 发现 → 连接中 → 已连接 → 收发消息
- 收到消息后按 NDEF 记录分卡展示，支持复制、查看原始数据及手动访问网页、打开应用、连接 Wi-Fi 和蓝牙配对；断开连接后仍保留接收内容，重新建立连接时清除上一会话内容
- 单次发送大小受当前 Nearby SDK 的 `ConnectionsClient.MAX_BYTES_DATA_SIZE` 限制；两端必须使用支持统一 NDEF 消息的版本，旧版文本前缀消息不再作为有效记录解析

---



### 4. 屏幕共享

- 端对端连接成功后可点击“进入屏幕共享”，对方确认进入并核对两端验证码后即可选择投屏方向；复用现有连接，不要求 NFC、不重新搜索或连接设备
- “写卡 / 卡模拟”页新增屏幕共享邀请入口；生成有效期为 3 分钟的自定义 NDEF 邀请，通过标准 Type 4 HCE 或应用内私有 AID 读取
- 系统扫描到有效邀请可打开投屏页并匹配对应设备；本应用的读卡页先展示邀请信息，由用户点击“加入屏幕共享”进入
- NFC 入口独立使用 Nearby `P2P_POINT_TO_POINT` 连接，以 NFC 邀请凭据及本次连接的原始认证令牌验证会话；端对端入口复用已建立的点对点连接，由投屏服务接管回调，双方手动确认验证码
- 两端均可提出投屏方向，另一端确认后，由发送端单独确认系统录屏授权；结束后可重新选择方向
- MediaProjection 采集画面，AVC 编码并通过 Nearby STREAM 传输；初始参数为最长边 960、短边不超过 540、15 fps、384 kbit/s，码率根据接收确认在 96 kbit/s 至 2 Mbit/s 间调整，尺寸按编码器要求对齐。正常传输最多允许两帧等待接收确认，实际流畅度取决于设备及无线链路
- 源端旋转时更新虚拟显示和编码参数，接收端保持画面比例，可横竖屏切换或全屏查看；发送端返回桌面后由前台服务维持会话，通知栏可停止连接
- 进入投屏后暂停普通记录发送；“结束共享”保留连接以便再次选择方向，“断开”或退出投屏页会结束连接。当前不采集音频、不支持远程操作。系统锁屏或撤销录屏授权会结束本次共享，进程终止后不自动恢复

## 环境要求

- Android Studio（推荐最新稳定版）+ JDK 17 及以上
- Android SDK Platform 37
- 真机要求：Android 14（API 34）及以上；Nearby P2P 需要支持蓝牙 / Wi-Fi
  > 模拟器不支持 NFC 读写与卡模拟，请务必使用真机调试；NFC 读写与卡模拟仍需支持 NFC
- 测试端对端需要**两台真机**；测试卡模拟需要另一台支持 NFC 的手机充当读卡器

## 构建与调试

| 构建类型 | 应用包名 | 应用名称 | 版本名 |
| --- | --- | --- | --- |
| debug（开发版） | `io.github.zyraxi21.nfc.debug` | NFC 通信 开发版 | 当前版本名追加 `-debug` |
| release（正式版） | `io.github.zyraxi21.nfc` | NFC 通信 | 当前版本名 |

在 Android Studio 中选择 `debug` 构建变体后直接运行，或执行 `./gradlew :app:assembleDebug` 构建开发版 APK。开发版可以与已安装的签名正式版共存，无需卸载正式版；两个版本的应用数据与权限分别管理。

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

### 屏幕共享

已有端对端连接时，直接在“端对端”页点击“进入屏幕共享”，在对方设备上点击“接受并进入屏幕共享”。核对两端验证码，双方点击“验证码一致，继续”，再选择投屏方向并确认系统录屏授权即可。此路径不要求设备支持或开启 NFC。

通过 NFC 连接时：

1. 两台设备都安装并至少手动打开过本应用，确认 Google Play services 可用，并开启 NFC 及蓝牙或 Wi-Fi
2. A 在“写卡 / 卡模拟”页点击“打开屏幕共享”，再点击“生成 NFC 邀请”，授权附近设备访问权限
3. B 将 NFC 区域靠近 A；支持标准 HCE 路由的设备可由系统打开本应用。若系统未打开，在 B 的屏幕共享页点击“碰一碰加入”再靠近；若在普通读卡页扫描，则点击邀请记录中的“加入屏幕共享”
4. 连接验证成功后，在任意一端选择“共享本机屏幕”或“查看对方屏幕”，另一端点击“同意”
5. 发送端点击“授权并开始共享”，在系统对话框选择共享区域并确认；接收端显示画面，支持旋转设备及全屏查看
6. 发送端可点击“返回桌面继续共享”；点击“结束共享”可重新选择方向，“断开”或通知中的“停止”结束整个连接

设备系统可能接管标准 Type 4 HCE 路由，因此冷启动唤起需要按机型验证。“碰一碰加入”沿用私有 AID 读取作为应用内入口。系统安全窗口的内容可能显示为空白，相关系统限制不能由应用绕过。协议说明与真机联调项目见 [屏幕共享说明](docs/screen-sharing.md)。

---

## NDEF 数据格式支持

| 数据类型 | 记录类型 | 说明 |
| --- | --- | --- |
| 文本 | Well Known `T`（RTD_TEXT） | 状态字节含语言代码长度与编码（UTF-8 / UTF-16） |
| 网址 | Well Known `U`（RTD_URI） | 支持 0x00–0x23 完整前缀表 |
| Wi-Fi | MIME `application/vnd.wfa.wsc` | Version + Credential；Credential 包含网络索引、SSID、认证类型、加密类型、密钥和通配 MAC |
| 蓝牙 | MIME `application/vnd.bluetooth.ep.oob` | 2 字节小端总长度 + 倒序 6 字节 MAC + 可选 EIR 设备名字段 |
| 应用程序 | External `android.com:pkg` | AAR（Android Application Record），支持写入、卡模拟及只读信息展示 |

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

- 应用程序标签的启动行为遵循 [Android AAR 分发规则](https://developer.android.com/develop/connectivity/nfc/nfc#aar)：目标设备应已安装对应应用；未安装时系统可能转到应用商店，是否需要确认由扫描设备的系统决定。
- **NFC P2P（Android Beam）自 Android 10 起被系统移除**，因此端对端功能改用 Google Nearby Connections（蓝牙 / Wi-Fi 直连）实现。
- Nearby Connections 从 2026 年底起不再自动开启 Wi-Fi / 蓝牙；应用会在启动广播或发现前检查无线装置，并在关闭时引导用户手动开启。
- 早期的独立 Activity 实现（`ReadCard.kt`、`WriteCard.kt`、`CardEmulationDeviceActivity`、`P2PCommunication.kt`）已全部清理，读写卡与卡模拟统一在 `MainActivity` + Compose 界面中完成。
- **小米钱包的「默认卡 / 智能选卡 / 双击电源键刷卡」会接管 NFC 控制器的整条 ISO-DEP 通道。** 开启时 `dumpsys nfc` 会显示 `mEnableHostRouting: false`、`Default route: secure element`，且 `Empty_AID` 指向 SE——此时任何**没有显式 host 条目**的 AID 都会被送进安全元件，本应用的 HCE 服务收不到任何 APDU（读卡器读到的是系统「碰一碰」的 `com.xiaomi.mi_connect_service:tap_top`，或安全元件返回的 `6A82`）。由于 **AID 路由在会话的第一个 `SELECT AID` 时即绑定**，首个 SELECT 一旦落到 SE，后续所有 APDU（包括平台自己的 NDEF 检查）都进不了 host，表现为完全读不到内容。
- **上述问题的修复**：关闭「默认卡 / 智能选卡 / 双击电源键刷卡」，并把「默认钱包应用」改为非钱包应用，然后重启一次 NFC。**判据是 `Default route: host` 与 `Empty_AID → 0x00`**（`mEnableHostRouting` 并非决定性字段，实测有设备在问题解决后它仍显示 `false`）。诊断命令：`adb shell dumpsys nfc | Select-String "Default route|Empty_AID|AID_F012345678|AID_D2760000850101"`。
- 系统扫描行为取决于 Android 版本和设备实现。根据 [Android NFC 文档](https://developer.android.com/develop/connectivity/nfc/nfc)，Android 17 扫描 HTTP(S) 标签后需要点击“打开链接”通知；Wi-Fi 配置和经典蓝牙 OOB 标签需要系统提供相应处理能力，蓝牙配对后能否建立连接还取决于外设服务。此类标签不提供 BLE 专用配对数据。
- 多记录 NDEF 消息不是操作脚本，系统不会依次执行全部记录。要明确触发网址、Wi-Fi 或蓝牙操作，建议单独写入对应类型的记录；混合标签中的应用记录也可能影响系统分发。
- Wi-Fi WSC 记录（`application/vnd.wfa.wsc`）包含 Version 和 Credential，后者包含 Network Index、SSID、Authentication Type、Encryption Type、Network Key 和通配 MAC 地址；SSID 最多 32 个 UTF-8 字节，个人网络密码为 8—63 个可打印 ASCII 字符或 64 位十六进制密钥，开放网络使用空密码。WSC 没有本应用原先为 WPA3 定义的认证值，写卡表单已移除此选项，也不生成 WEP 配置。
- 蓝牙 OOB 记录（`application/vnd.bluetooth.ep.oob`）按 [Bluetooth GAP 规范](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-60/out/en/host/generic-access-profile.html)使用小端总长度、反序 BD_ADDR 和 EIR Complete Local Name；名称可省略，最多 248 个 UTF-8 字节。Wi-Fi 和蓝牙的旧版自定义载荷不再作为有效标准配置解析，仍可查看原始数据。
- 记录上的 Wi-Fi 按钮使用 [Android 系统添加网络接口](https://developer.android.com/develop/connectivity/wifi/wifi-save-network-passpoint-config)。64 位十六进制密钥及其他不受此接口直接支持的配置，改用“复制密码并打开 Wi-Fi 设置”；蓝牙按钮使用系统配对流程，不能保证任意外设自动建立业务连接。
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

底部导航使用 [Microsoft Fluent UI System Icons](https://github.com/microsoft/fluentui-system-icons) 的 MIT 许可矢量图标，来源及许可证见 [third_party/fluentui-system-icons](third_party/fluentui-system-icons/README.md)。

构建时引用的第三方组件及其许可证：

| 依赖 | 许可证 |
| --- | --- |
| AndroidX（core-ktx、appcompat、lifecycle、navigation、activity-compose 等） | Apache-2.0 |
| Jetpack Compose（ui、material3、ui-tooling） | Apache-2.0 |
| Material Components for Android | Apache-2.0 |
| ConstraintLayout / ConstraintLayout Compose | Apache-2.0 |
| Kotlin 标准库 | Apache-2.0 |
| Google Play services — Nearby Connections | 闭源专有，受 [Google APIs 服务条款](https://developers.google.com/terms) 约束 |

上表中除 Nearby Connections 外的组件均为 Apache-2.0，与 GPLv3 兼容。Nearby Connections 属于闭源专有组件，不在本项目源码的许可范围内，其使用需另行遵守 Google 的条款。
