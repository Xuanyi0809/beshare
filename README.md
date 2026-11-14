<div align="center">

# 快传 · BeShare

**两台安卓手机，面对面，秒传文件**

无服务器 · 无网页 · 无账号 · 无广告 · 纯本地直连

[官网下载页](https://beshare.rth1.xyz) · [GitHub Releases](https://github.com/Xuanyi0809/beshare/releases)

![platform](https://img.shields.io/badge/Platform-Android%206.0%2B-3DDC84?logo=android&logoColor=white)
![version](https://img.shields.io/badge/Version-1.5-2563EB)
![size](https://img.shields.io/badge/APK-49%20KB-2563EB)
![license](https://img.shields.io/badge/License-MIT-blue)
![org](https://img.shields.io/badge/Org-CircleHub-2563EB)

</div>

---

## 这是什么

快传是一个**完全本地运行**的安卓文件互传工具。两台手机都打开 App，接收端停在「接收」页，发送端在设备列表里**选中唯一目标**，即可发送文字、图片或任意文件。

没有中转服务器，没有网页版，没有账号体系，文件不经过任何第三方。数据只在两台设备之间走：优先 Wi-Fi Direct 直连，也可切到应用内热点；大文件自动按线路数分块，多条连接并行传输。

> 它解决的正是「微信传文件压图、网盘要登录、快传类 App 满屏广告」这些事——打开就用，传完即走。

## 特性

- **定向传输**：发送前必须选中某台设备，不会向局域网/蓝牙/P2P 里所有设备广播
- **四个独立开关**：设置在「设置」页里，Wi‑Fi 直连、蓝牙、P2P 握手、应用内热点可分别开关
- **多线路**
  - `Wi-Fi Direct (P2P)`：接收端建组当 GO，发送端加入后走 TCP，适合大文件
  - `应用内热点`：接收端开 LocalOnlyHotspot，SSID/密码经蓝牙握手交给发送端
  - `Bluetooth RFCOMM`：负责发现、握手与兜底，P2P 不可用时照样能传
- **大文件自动分段**：≥4 MB 按 2–4 段切块，可同时走多条 TCP 连接；有蓝牙线路时再分一块给蓝牙，收齐后合并落盘
- **全类型文件**：系统文件选择器（SAF）选任意文件，不申请存储权限也能发
- **文字消息**：直接发送文本，接收端可一键复制
- **MD3 设计**：Material 3 配色与形态（大圆角卡片、分区设置页、胶囊标签、状态卡），跟随系统深色模式
- **更新检查**：启动自动读取 `version.json`，设置页可手动检查；站点不可达时自动走 jsDelivr / GitHub raw 备线
- **落盘可见**：Android 10+ 写入 `下载/快传`（MediaStore），旧版本写入公共下载目录；保留原文件名，同名自动加序号
- **零依赖**：不依赖 AndroidX / Material Components，纯 framework Java，APK 仅 49 KB
- **无后台服务**：不需要常驻通知、不需要登录，退出即结束

## 界面与交互

```
┌─────────────────────────────┐
│ 快传                    [设置]│
│ 多线路传输 · 无服务器         │
│                             │
│ ┌────────┐  ┌────────┐      │
│ │  发送  │  │  接收  │      │
│ └────────┘  └────────┘      │
│                             │
│ 正在搜索附近的设备…          │
│ 本机蓝牙名：Mate 50          │
│ 附近设备 · 点一行选中  重新搜索│
│ ┌─────────────────────────┐ │
│ │ W  对方的手机            │ │
│ │    Wi-Fi 直连 · 02:..   │ │
│ └─────────────────────────┘ │
│ ┌─────────────────────────┐ │
│ │ B  对方的手机            │ │
│ │    蓝牙 · 02:..         │ │
│ └─────────────────────────┘ │
│ 未选择文件                   │
│ [ 选择文件 ]  [ 发送文本 ]   │
│ [          发送          ]   │
└─────────────────────────────┘
```

- `W` = Wi-Fi Direct 设备，`B` = 蓝牙设备；同一台机器可能两项都出现，优先选 `W`
- 右上角「设置」里集中管理 P2P、热点、Wi‑Fi、蓝牙开关，以及官网/更新与关于
- 点状态卡或「重新搜索」可重新发现
- 传输中显示百分比进度，发送按钮自动锁定防止重复触发

## 工作原理

```
发送端                                  接收端
  │                                        │
  ├─ 蓝牙发现 / P2P discoverPeers ────────►│ 已在「接收」页
  │                                        │ createGroup() 成为 GO
  │◄────────── 设备列表（名称+地址）────────┤ 蓝牙可发现 300s + RFCOMM listen
  │                                        │
  │  用户点选唯一目标                        │
  │                                        │
  ├─ P2P connect(deviceAddress) ──────────►│
  │◄──────── groupOwnerAddress ────────────┤
  ├─ TCP :8988 ───────────────────────────►│ 落盘 MediaStore
  │   JSON 头 + 原始字节流                   │
  │                                        │
  └─ P2P 失败 → RFCOMM :UUID ─────────────►│ 同一套协议
```

**为什么不是「WiFi + 蓝牙」的简单方案？**
单靠 WiFi 需要两台设备先在同一路由器/热点下，还得额外解决「谁是谁」的发现问题；单靠蓝牙速度只有百 KB/s 级。把蓝牙用于发现与握手、P2P 用于传输，既不需要路由器，也能跑满直连带宽；P2P 被系统限制时还能整体退回蓝牙，保证「一定能传」。

## 传输协议

一条连接 = 一行 UTF-8 JSON 头 + 可选二进制体，字节流按 `\n` 分界（手工逐字节读取，避免 `BufferedReader` 预读吞掉后续二进制）。

```json
{"type":"file","name":"photo.jpg","size":2451234,"text":"","from":"Mate 50"}
```

```json
{"type":"text","name":"文本消息","size":0,"text":"你好，文件在下载/快传","from":"Mate 50"}
```

完整规范（字段表、分帧规则、超时、安全模型）见 **[docs/PROTOCOL.md](docs/PROTOCOL.md)**。

- `type=file`：头之后紧跟 `size` 字节原始数据
- `type=text`：内容在头的 `text` 字段，无二进制体
- 端口固定 `8988`；蓝牙服务 UUID `6f2b7a1e-2c3d-4e5f-8a9b-0c1d2e3f4a5b`

## 权限说明

| 权限 | 用途 |
|---|---|
| `BLUETOOTH_SCAN / CONNECT / ADVERTISE`（Android 12+） | 蓝牙搜索、连接、被搜索 |
| `BLUETOOTH / BLUETOOTH_ADMIN`（≤ Android 11） | 旧版本蓝牙兼容 |
| `ACCESS_FINE_LOCATION` | Android 10 及以下扫描蓝牙/ P2P 的系统要求 |
| `NEARBY_WIFI_DEVICES`（Android 13+） | Wi-Fi Direct 发现与建组 |
| `ACCESS/CHANGE_WIFI_STATE` | 建立 P2P 组 |
| `INTERNET` | **仅**用于本地 Socket，不访问互联网 |
| `WRITE_EXTERNAL_STORAGE`（≤ Android 9） | 旧版本写入下载目录 |

App 不申请通讯录、短信、相机、后台定位，也不含任何统计 SDK。

## 下载与安装

- 从 [Releases](../../releases) 下载 `beshare-x.x.apk`，或直接装官网 [beshare.rth1.xyz](https://beshare.rth1.xyz)
- 1.2 起包名为 `com.circlehub.beshare`，与 1.1 不是同一个应用标识，升级前请先卸载旧版
- 需要 Android 6.0（API 23）及以上；已在华为 Mate 50（HarmonyOS 4.0）上适配蓝牙兜底路径
- 两端使用同一签名即可覆盖升级

> 首次连接蓝牙时系统会弹出配对框，这是 Android 的安全机制，允许即可。

## 使用步骤

1. 两台手机都安装并打开快传
2. 接收端：点「接收」，等待状态显示「直连热点已建立」；允许蓝牙可发现与配对
3. 发送端：点「发送」→ 在列表里选中对方（优先 `W`）→「选择文件」或「发送文本」→「发送」
4. 接收端文件出现在 `下载/快传`，文字消息直接显示在列表里
5. 若 `W` 连不上（部分 HarmonyOS/企业定制系统限制 P2P），选同一设备的 `B` 走蓝牙

## 从源码构建

项目**不使用 Gradle**，只需要 Android SDK build-tools 与 JDK：

```bash
export ANDROID_JAR=$HOME/Android/Sdk/platforms/android-34/android.jar
export BUILD_TOOLS=$HOME/Android/Sdk/build-tools/34.0.0
export KEYSTORE=/path/to/your.keystore
export KS_PASS=yourpass
export KEY_ALIAS=youralias

./build.sh ../快传.apk
```

`build.sh` 依次执行：`aapt2 compile/link` → `javac -source 8 -target 8` → `d8 --min-api 23` → `zipalign` → `apksigner`。

目录结构：

```
beshare/
├── AndroidManifest.xml
├── build.sh
├── CHANGELOG.md
├── docs/
│   ├── PROTOCOL.md          # 传输协议规范
│   └── ARCHITECTURE.md      # 架构与线程模型
├── res/
│   ├── drawable/ic_launcher.xml
│   └── values/strings.xml
└── src/com/circlehub/beshare/MainActivity.java   # 全部逻辑与 MD3 UI
```

## 兼容性与已知限制

- **仅 Android**：iOS 系统不开放 Wi-Fi Direct / RFCOMM 给第三方互传场景，不做跨平台承诺
- **HarmonyOS / 定制 ROM**：部分机型会限制 `createGroup`，此时只能走蓝牙，速度约 100–300 KB/s
- **蓝牙需配对**：未配对设备首次连接会弹系统配对框
- **无加密**：链路是本地直连，当前版本不做应用层加密；公共场合传敏感文件请注意
- **单连接**：一次只服务一个发送端，不支持多人同时投递
- **后台限制**：传输期间请保持 App 在前台，避免被系统冻结

## Roadmap

- [ ] 应用层加密（ECDH + AES-GCM）
- [ ] 断点续传与多文件队列
- [ ] 传输记录持久化
- [ ] BLE 发现替代经典蓝牙可发现流程
- [ ] 二维码 / 数字码配对，免去系统配对弹窗

## 贡献

欢迎 Issue 与 PR。提交前请确保：

1. 不引入服务端依赖或联网上报
2. 保持 `minSdk 23`、无 AndroidX 依赖，APK 体积可控
3. 新功能需在至少两台真机上验证（或说明验证环境）

## 文档

- [传输协议规范](docs/PROTOCOL.md)
- [架构说明](docs/ARCHITECTURE.md)
- [更新日志](CHANGELOG.md)

## License

[MIT](LICENSE) © 2026 CircleHub
