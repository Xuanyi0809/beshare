# 架构说明

## 单 Activity + 线程池

整个 App 只有 `MainActivity` 一个组件，无 Service、无 Provider、无第三方依赖。

```
MainActivity (UI 线程)
├── 视图构建：纯 Java 代码 + GradientDrawable 实现 MD3 外观
├── 设置页：setContentView 切换整页，四个独立开关 + 持久化
├── 广播接收：蓝牙 / Wi-Fi Direct 系统广播
├── 状态机：mode = 0 发送 / 1 接收
└── ExecutorService（cached）
    ├── BT accept 循环
    ├── TCP accept 循环
    ├── BT 连接 + 发送
    ├── 分块发送（每段一线程，TCP/蓝牙混合）
    └── 接收处理（每连接一线程，按 id/start 写入缓存）
```

## 关键状态

| 字段 | 含义 |
|---|---|
| `mode` | 当前页面 |
| `target` | 用户选中的唯一目标设备 |
| `sendPending` | 已发起 P2P 连接、等待 `groupFormed` 后发送 |
| `busy` | 传输中，锁定发送按钮 |
| `serversRunning` | 接收端监听是否已启动（跨页面切换保持） |
| `groupReady` / `localIp` | P2P 建组结果与 GO 地址 |
| `wifiOn` / `btOn` | Wi‑Fi 直连、蓝牙总开关（SharedPreferences） |
| `p2pMode` / `hotspotOn` | 蓝牙握手后切 P2P、应用内热点开关 |
| `chunkJobs` | 接收端的分块任务表（id → 缓存文件/已完成块） |

## UI 与 MD3

因项目不使用 AndroidX / Material Components，MD3 观感由以下手段实现：

- 圆角卡片：`GradientDrawable.setCornerRadius`
- 胶囊标签/按钮：圆角 = 高度的一半
- 配色：种子蓝 `#2563EB`，浅色底 `#F4F7FC`、卡片白；深色底 `#101418`、卡片 `#1B2027`
- 设置页：分区卡片 + 原生 `Switch`（thumb 着色主色），返回键/or返回按钮退出
- 深浅色：读取 `UI_MODE_NIGHT_MASK`，同步设置状态栏/导航栏颜色

## 生命周期

- `onDestroy`：关闭蓝牙/TCP 监听、取消蓝牙发现、`removeGroup`、停线程池、清理分块缓存
- 传输期间需保持前台；未使用前台服务，避免常驻通知
