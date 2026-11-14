# 架构说明

## 单 Activity + 线程池

整个 App 只有 `MainActivity` 一个组件，无 Service、无 Provider、无第三方依赖。

```
MainActivity (UI 线程)
├── 视图构建：纯 Java 代码 + GradientDrawable 实现 MD3 外观
├── 广播接收：蓝牙 / Wi-Fi Direct 系统广播
├── 状态机：mode = 0 发送 / 1 接收
└── ExecutorService（cached）
    ├── BT accept 循环
    ├── TCP accept 循环
    ├── BT 连接 + 发送
    └── 接收处理（每连接一线程）
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

## UI 与 MD3

因项目不使用 AndroidX / Material Components，MD3 观感由以下手段实现：

- 圆角卡片：`GradientDrawable.setCornerRadius`
- 胶囊标签/按钮：圆角 = 高度的一半
- 配色：种子蓝 `#4F7CFF`，浅色底 `#F5F6FA`、卡片白；深色底 `#101418`、卡片 `#1B2027`
- 深浅色：读取 `UI_MODE_NIGHT_MASK`，同步设置状态栏/导航栏颜色

## 生命周期

- `onDestroy`：关闭蓝牙/TCP 监听、取消蓝牙发现、`removeGroup`、停线程池
- 传输期间需保持前台；未使用前台服务，避免常驻通知
