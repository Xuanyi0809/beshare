# 快传传输协议规范（v1）

本文描述「快传」两端之间的发现、建链与数据传输规则。协议面向**本地直连**，无中转、无鉴权服务器；同一份协议同时用于 Wi-Fi Direct（TCP）与蓝牙 RFCOMM 两条承载通道。

- 版本：v1（随 App 1.0 / 1.1 发布）
- 字节序：大端（网络序），仅用于整数；本项目实际字段均为文本
- 字符集：UTF-8
- 默认端口：`8988/TCP`
- 蓝牙服务 UUID：`6f2b7a1e-2c3d-4e5f-8a9b-0c1d2e3f4a5b`
- 蓝牙服务名：`快传`

---

## 1. 角色模型

| 角色 | 行为 |
|---|---|
| 接收端（Receiver） | 打开「接收」页 → 蓝牙可被发现（300s）+ RFCOMM `listen` + Wi-Fi Direct `createGroup()`（本机成为 GO）+ TCP `:8988` 监听 |
| 发送端（Sender） | 打开「发送」页 → 蓝牙 `startDiscovery` + Wi-Fi Direct `discoverPeers` → 用户**单选**一台设备 → 建链 → 发送 |

关键约束：

1. **必须点选目标**。协议层没有广播/组播投递，一个连接只服务一个接收端。
2. **接收端先就绪**。发送端建链前，接收端必须已在「接收」页完成监听与建组。
3. **GO 固定为接收端**。由接收端 `createGroup()` 主动成为 Group Owner，发送端只以 client 身份 `connect`，避免角色竞争导致谁当 GO 不确定。

---

## 2. 发现（Discovery）

### 2.1 蓝牙发现

- 发送端调用 `BluetoothAdapter.startDiscovery()`，通过 `ACTION_FOUND` 收集 `{name, MAC}`。
- 接收端调用 `ACTION_REQUEST_DISCOVERABLE`（`EXTRA_DISCOVERABLE_DURATION = 300`），使经典蓝牙可被搜索。
- 限制：Android 12+ 需要 `BLUETOOTH_SCAN / BLUETOOTH_CONNECT`；Android 10 及以下扫描还需要定位权限。
- 蓝牙发现是**尽力而为**：设备已配对但不可发现时搜不到，可退化为在系统蓝牙设置中先配对，再用「蓝牙」条目连接。

### 2.2 Wi-Fi Direct 发现

- 发送端 `discoverPeers()`，回调 `requestPeers()` 获取 `WifiP2pDeviceList`，取 `deviceName` + `deviceAddress`。
- 接收端通过 `createGroup()` 建立 group，group 名即设备名，发送端可见。
- Android 13+ 需要 `NEARBY_WIFI_DEVICES`；Android 12 及以下需要 `ACCESS_FINE_LOCATION`。

### 2.3 列表合并

UI 将两路结果合并为统一列表，按 `(kind, address)` 去重，同一台设备可能出现两条：

| 标记 | kind | 地址字段 | 传输通道 |
|---|---|---|---|
| `W` | 0 | `WifiP2pDevice.deviceAddress` | Wi-Fi Direct + TCP |
| `B` | 1 | 蓝牙 MAC | RFCOMM |

优先级：`W` > `B`。`W` 失败（建组失败、连接超时、系统限制）时由用户改选 `B`。

---

## 3. 建链（Connect）

### 3.1 Wi-Fi Direct

1. 接收端 `createGroup()`；失败时先 `removeGroup()` 再重试一次。
2. 发送端 `connect(WifiP2pConfig{deviceAddress, groupOwnerIntent=0})`。
3. 双方收到 `WIFI_P2P_CONNECTION_CHANGED_ACTION`，`WifiP2pInfo.groupFormed == true`。
4. 发送端从 `groupOwnerAddress` 取接收端 IP（通常 `192.168.49.1`），向 `IP:8988` 发起 TCP。
5. **超时**：发送端等待连接信息最多 20s；TCP `connect` 超时 8s。

### 3.2 蓝牙 RFCOMM

1. 接收端 `listenUsingRfcommWithServiceRecord("快传", UUID)`，循环 `accept()`。
2. 发送端 `createRfcommSocketToServiceRecord(UUID)`；若失败，回退反射调用隐藏 API `createRfcommSocket(1)`。
3. 未配对设备首次连接会触发系统配对弹窗（Android 安全机制，协议层不绕过）。
4. 发送端在连接前调用 `cancelDiscovery()`，避免发现过程拖慢/阻断 RFCOMM。

### 3.3 建链失败语义

建链失败不产生协议层错误码，仅通过 UI 状态提示；发送端可原样重试或改选另一通道。协议**无重连、无重传**。

---

## 4. 数据帧格式

一次传输 = **1 行 JSON 头** + **可选二进制体**。

```
+------------------------------+-------------------+
| JSON header (UTF-8) + '\n'   | body (size bytes) |
+------------------------------+-------------------+
```

### 4.1 头部分界规则

- 头以单个 `LF`（`0x0A`）结束；`CR`（`0x0D`）在读取时被丢弃。
- 发送端只写 `\n`，不写 `\r\n`。
- 接收端**逐字节读取**直到 `\n`，不使用 `BufferedReader`，避免预读吞掉紧随其后的二进制体。
- 头上限 64 KiB；超限视为非法帧，断开连接。

### 4.2 字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | 是 | `file` 或 `text` |
| `name` | string | 是 | 文件名；`text` 时为展示名（固定 `文本消息`） |
| `size` | number | 是 | 二进制体字节数；`text` 时为 `0` |
| `text` | string | 否 | `type=text` 时的正文；`file` 时为空串 |
| `from` | string | 否 | 发送端蓝牙名或 `Build.MODEL`，仅用于展示 |

未知字段必须忽略；未知 `type` 按 `file` 处理（向前兼容策略）。

### 4.3 示例

文件：

```json
{"type":"file","name":"IMG_20261114_121500.jpg","size":2451234,"text":"","from":"Mate 50"}
```

紧跟 2,451,234 字节原始内容。

文本：

```json
{"type":"text","name":"文本消息","size":0,"text":"晚上一起吃饭？","from":"Mate 50"}
```

无二进制体。

---

## 5. 传输与落盘

### 5.1 发送端

1. 写头 + `flush`。
2. `type=file`：从 SAF `content://` 打开 `InputStream`，8 KiB 分块写入 socket，按已发送字节更新进度。
3. 关闭输出流；不等待应用层 ACK，以“写完全部字节”为完成。

### 5.2 接收端

1. 读头 → 解析。
2. `type=text`：直接入列表，可复制，不落盘。
3. `type=file`：
   - 计算唯一文件名（同名追加 ` (1)`、` (2)`…）
   - Android 10+：`MediaStore.Downloads` 插入，`RELATIVE_PATH = Download/快传`
   - Android 9 及以下：`/sdcard/Download/快传/` + `WRITE_EXTERNAL_STORAGE`
   - 8 KiB 分块写入，累计 `size` 字节后停止
4. 完整性判定：`size > 0 && received < size` 视为中断，删除半成品（MediaStore 条目或本地文件）；否则视为完成。
5. 不校验哈希；协议的完整性依赖 TCP/RFCOMM 的链路校验与字节计数。

### 5.3 超时与取消

| 环节 | 超时 |
|---|---|
| TCP connect | 8 s |
| 等待 P2P 连接信息 | 20 s（UI 侧） |
| 读头部 | 60 s（`SO_TIMEOUT`） |
| 读主体 | 60 s 空闲超时（每读到数据即重置） |

任一端关闭 socket 即视为取消；接收端按 5.2 第 4 条清理。

---

## 6. 安全模型

- **链路层**：蓝牙配对提供链路密钥；Wi-Fi Direct group 使用 WPA2。
- **应用层**：v1 **不加密、不鉴权**。任何能连上该端口/服务的设备都可投递文件；传输内容为明文。
- 接收端不校验发送者身份，`from` 字段仅供参考，可伪造。
- `INTERNET` 权限仅用于本地 socket，App 不包含任何联网上报逻辑。
- 建议：仅在可信环境使用；不要用它传输密钥、证件等敏感材料。

后续版本计划：ECDH 协商 + AES-GCM 应用层加密、二维码/数字码配对、可选接收确认。

---

## 7. 兼容性备注

- **HarmonyOS / 定制 ROM**：可能限制 `createGroup()` 或后台 Wi-Fi，P2P 建组失败时协议整体退回 RFCOMM，语义不变。
- **蓝牙吞吐**：经典蓝牙实际约 100–300 KB/s，适合小文件兜底；大文件优先 P2P。
- **IPv6**：v1 仅使用 IPv4 的 `groupOwnerAddress`。
- **多连接**：实现上 accept 循环可为多个连接各起线程，但产品设计面向单发送端；并发写入时各自独立落盘。
