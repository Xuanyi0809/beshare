package com.circlehub.beshare;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final UUID APP_UUID = UUID.fromString("6f2b7a1e-2c3d-4e5f-8a9b-0c1d2e3f4a5b");
    static final int PORT = 8988;
    static final String BT_NAME = "快传";

    int mode = 0; // 0 发送 1 接收

    WifiP2pManager wfd;
    WifiP2pManager.Channel wch;
    BluetoothAdapter bt;
    BluetoothServerSocket btServer;
    ServerSocket tcpServer;
    boolean serversRunning = false;
    boolean groupReady = false;
    String localIp = "";

    final List<Dev> devices = new ArrayList<Dev>();
    Dev target;
    Uri sendUri;
    String sendName = "";
    long sendSize = 0;
    boolean sendPending = false;
    boolean p2pMode = false;
    WifiManager.LocalOnlyHotspotReservation hotRes;
    String hotSsid = "", hotPass = "";
    ConnectivityManager.NetworkCallback hotspotCb;
    long sendToken = 0;

    final ExecutorService pool = Executors.newCachedThreadPool();
    final Handler ui = new Handler(Looper.getMainLooper());

    // UI
    LinearLayout root, listBox, fileRow, progressRow, scanRow, btnRow;
    boolean busy = false;
    TextView chipSend, chipRecv, chipP2P, status, fileInfo, sendBtn, recvBtn, progText, localName;
    ScrollView scroll;
    ProgressBar progBar;
    boolean dark = false;
    int cBg, cCard, cText, cSub, cPrimary, cOnPrimary, cPrimarySoft, cLine;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        cBg = dark ? 0xFF101418 : 0xFFF5F6FA;
        cCard = dark ? 0xFF1B2027 : 0xFFFFFFFF;
        cText = dark ? 0xFFE8ECF2 : 0xFF11161C;
        cSub = dark ? 0xFF97A1AE : 0xFF67707C;
        cPrimary = 0xFF4F7CFF;
        cOnPrimary = 0xFFFFFFFF;
        cPrimarySoft = dark ? 0xFF25324F : 0xFFE6ECFF;
        cLine = dark ? 0xFF2A313A : 0xFFE3E7EF;

        Window w = getWindow();
        w.setStatusBarColor(cBg);
        w.setNavigationBarColor(cBg);
        if (!dark && Build.VERSION.SDK_INT >= 23) {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        buildUi();
        initRadios();
        ensurePermissions();
    }

    /* ---------------- UI ---------------- */

    int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    GradientDrawable round(int color, int radius, int stroke, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        if (stroke > 0) g.setStroke(dp(stroke), strokeColor);
        return g;
    }

    TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    void updateP2P() {
        if (chipP2P == null) return;
        chipP2P.setText(p2pMode ? "P2P 开" : "P2P");
        chipP2P.setTextColor(p2pMode ? cOnPrimary : cSub);
        chipP2P.setBackground(round(p2pMode ? cPrimary : cCard, 18, p2pMode ? 0 : 1, cLine));
    }

    void openSite() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://beshare.rth1.xyz")));
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        root.setFitsSystemWindows(true);

        // 顶部
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(18), dp(20), dp(6));
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("快传", 27, cText, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        chipP2P = text("P2P", 12, cSub, true);
        chipP2P.setGravity(Gravity.CENTER);
        chipP2P.setPadding(dp(10), dp(7), dp(10), dp(7));
        chipP2P.setBackground(round(cCard, 18, 1, cLine));
        chipP2P.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                p2pMode = !p2pMode;
                updateP2P();
                if (mode == 1) {
                    stopHotspot();
                    startReceiver();
                } else {
                    status.setText(p2pMode ? "P2P 已开启：发送前会先蓝牙握手，再用热点/直连传输" : "已切回普通传输");
                }
            }
        });
        TextView update = text("官网 · 更新", 12, cPrimary, true);
        update.setGravity(Gravity.CENTER);
        update.setPadding(dp(12), dp(7), dp(12), dp(7));
        update.setBackground(round(cPrimarySoft, 18, 0, 0));
        update.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSite(); }
        });
        titleRow.addView(title);
        titleRow.addView(chipP2P);
        LinearLayout.LayoutParams p2plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p2plp.rightMargin = dp(8);
        chipP2P.setLayoutParams(p2plp);
        titleRow.addView(update);
        updateP2P();
        head.addView(titleRow);
        TextView sub = text("v1.4 · Wi-Fi 直连 · 蓝牙 · 无服务器 · 不经过网页", 12, cSub, false);
        sub.setPadding(0, dp(4), 0, 0);
        head.addView(sub);
        root.addView(head);

        // Tab
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(16), dp(10), dp(16), dp(4));
        chipSend = text("发送", 15, cOnPrimary, true);
        chipSend.setGravity(Gravity.CENTER);
        chipSend.setPadding(0, dp(11), 0, dp(11));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp1.rightMargin = dp(10);
        chipSend.setLayoutParams(lp1);
        chipRecv = text("接收", 15, cText, true);
        chipRecv.setGravity(Gravity.CENTER);
        chipRecv.setPadding(0, dp(11), 0, dp(11));
        chipRecv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tabs.addView(chipSend);
        tabs.addView(chipRecv);
        root.addView(tabs);
        chipSend.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchMode(0); }
        });
        chipRecv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchMode(1); }
        });

        // 状态卡
        status = text("准备中…", 13, cText, false);
        status.setPadding(dp(16), dp(13), dp(16), dp(13));
        status.setBackground(round(cPrimarySoft, 18, 0, 0));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(16); slp.rightMargin = dp(16); slp.topMargin = dp(8);
        status.setLayoutParams(slp);
        status.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (mode == 0) {
                    devices.clear(); target = null; renderDevices();
                    status.setText("重新搜索中…");
                    startBtDiscovery(); startWfdDiscovery();
                }
            }
        });
        root.addView(status);

        localName = text("", 12, cSub, false);
        localName.setPadding(dp(20), dp(6), dp(20), 0);
        root.addView(localName);

        scanRow = new LinearLayout(this);
        scanRow.setOrientation(LinearLayout.HORIZONTAL);
        scanRow.setGravity(Gravity.CENTER_VERTICAL);
        scanRow.setPadding(dp(20), dp(6), dp(20), 0);
        TextView scanHint = text("附近设备 · 点一行选中目标", 12, cSub, false);
        scanHint.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView rescan = text("重新搜索", 12, cPrimary, true);
        rescan.setPadding(dp(10), dp(4), dp(6), dp(4));
        rescan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (mode != 0) return;
                devices.clear(); target = null; renderDevices();
                status.setText("重新搜索中…");
                startBtDiscovery(); startWfdDiscovery();
            }
        });
        scanRow.addView(scanHint);
        scanRow.addView(rescan);
        root.addView(scanRow);

        // 列表
        scroll = new ScrollView(this);
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        scp.leftMargin = dp(16); scp.rightMargin = dp(16); scp.topMargin = dp(10);
        scroll.setLayoutParams(scp);
        scroll.setClipToPadding(false);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, 0, 0, dp(8));
        scroll.addView(listBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);

        // 底部操作
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), dp(8), dp(16), dp(18));

        fileInfo = text("未选择文件", 13, cSub, false);
        fileInfo.setPadding(dp(4), 0, dp(4), dp(10));
        bottom.addView(fileInfo);

        btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView pick = text("选择文件", 14, cText, true);
        pick.setGravity(Gravity.CENTER);
        pick.setPadding(0, dp(12), 0, dp(12));
        pick.setBackground(round(cCard, 24, 1, cLine));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        plp.rightMargin = dp(10);
        pick.setLayoutParams(plp);
        TextView txt = text("发送文本", 14, cText, true);
        txt.setGravity(Gravity.CENTER);
        txt.setPadding(0, dp(12), 0, dp(12));
        txt.setBackground(round(cCard, 24, 1, cLine));
        txt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        btnRow.addView(pick);
        btnRow.addView(txt);
        bottom.addView(btnRow);

        sendBtn = text("发送", 16, cOnPrimary, true);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setPadding(0, dp(14), 0, dp(14));
        sendBtn.setBackground(round(cPrimary, 26, 0, 0));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        sendBtn.setLayoutParams(blp);
        bottom.addView(sendBtn);

        recvBtn = text("接收", 16, cOnPrimary, true);
        recvBtn.setGravity(Gravity.CENTER);
        recvBtn.setPadding(0, dp(14), 0, dp(14));
        recvBtn.setBackground(round(cPrimary, 26, 0, 0));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        recvBtn.setLayoutParams(rlp);
        recvBtn.setVisibility(View.GONE);
        recvBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (serversRunning) {
                    makeDiscoverable();
                    createGroup();
                    status.setText("接收中：让对方在「发送」里选中本机");
                } else {
                    startReceiver();
                }
            }
        });
        bottom.addView(recvBtn);

        fileRow = new LinearLayout(this);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);
        fileRow.setGravity(Gravity.CENTER_VERTICAL);
        fileRow.setVisibility(View.GONE);
        progBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progBar.setMax(1000);
        LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(0, dp(6), 1);
        progBar.setLayoutParams(prlp);
        progText = text("0%", 12, cSub, false);
        progText.setPadding(dp(10), 0, 0, 0);
        fileRow.addView(progBar);
        fileRow.addView(progText);
        LinearLayout.LayoutParams frlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        frlp.topMargin = dp(8);
        fileRow.setLayoutParams(frlp);
        bottom.addView(fileRow);

        root.addView(bottom);
        setContentView(root);

        pick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFile(); }
        });
        txt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askText(); }
        });
        sendBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doSend(); }
        });

        switchMode(0);
    }

    void applyChips() {
        chipSend.setBackground(round(mode == 0 ? cPrimary : cCard, 24, mode == 0 ? 0 : 1, cLine));
        chipSend.setTextColor(mode == 0 ? cOnPrimary : cText);
        chipRecv.setBackground(round(mode == 1 ? cPrimary : cCard, 24, mode == 1 ? 0 : 1, cLine));
        chipRecv.setTextColor(mode == 1 ? cOnPrimary : cText);
        fileRow.setVisibility(View.GONE);
        progressRowSet(false);
        boolean send = mode == 0;
        fileInfo.setVisibility(send ? View.VISIBLE : View.GONE);
        btnRow.setVisibility(send ? View.VISIBLE : View.GONE);
        sendBtn.setVisibility(send ? View.VISIBLE : View.GONE);
        recvBtn.setVisibility(send ? View.GONE : View.VISIBLE);
        if (!send) recvBtn.setText(serversRunning ? "重新准备接收" : "接收");
        if (scanRow != null) scanRow.setVisibility(send ? View.VISIBLE : View.GONE);
    }

    void progressRowSet(boolean show) {
        fileRow.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    void switchMode(int m) {
        if (m == mode && serversRunning && m == 1) return;
        mode = m;
        applyChips();
        devices.clear();
        target = null;
        listBox.removeAllViews();
        if (m == 0) {
            stopHotspot();
            status.setText("正在搜索附近的设备…");
            startBtDiscovery();
            startWfdDiscovery();
            renderDevices();
        } else {
            status.setText("点下方「接收」按钮开始等待对方");
            recvBtn.setText("接收");
            renderReceiveHint();
        }
    }

    /* ---------------- 初始化 ---------------- */

    void initRadios() {
        try {
            wfd = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
            if (wfd != null) wch = wfd.initialize(this, Looper.getMainLooper(), null);
        } catch (Throwable t) { wfd = null; }
        try { bt = BluetoothAdapter.getDefaultAdapter(); } catch (Throwable t) { bt = null; }
        if (bt != null && !bt.isEnabled()) {
            try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Throwable ignored) {}
        }
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        try { registerReceiver(radioRx, f); } catch (Throwable ignored) {}
        try {
            if (bt != null) localName.setText("本机蓝牙名：" + bt.getName());
        } catch (Throwable ignored) {}
    }

    String[] wantedPerms() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES};
        }
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    void ensurePermissions() {
        List<String> need = new ArrayList<String>();
        for (String p : wantedPerms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        boolean all = true;
        for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) all = false;
        if (!all) {
            status.setText("权限未全部授予：蓝牙/定位/附近设备权限会影响搜索与直连");
            Toast.makeText(this, "请在设置中允许蓝牙与附近设备权限", Toast.LENGTH_LONG).show();
        }
        if (mode == 0) { startBtDiscovery(); startWfdDiscovery(); }
    }

    /* ---------------- 广播 ---------------- */

    final BroadcastReceiver radioRx = new BroadcastReceiver() {
        public void onReceive(Context c, Intent in) {
            String a = in.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                BluetoothDevice d = in.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                String nm = null;
                try { nm = d.getName(); } catch (Throwable ignored) {}
                if (TextUtils.isEmpty(nm)) nm = "蓝牙设备";
                addDevice(new Dev(nm, safeAddr(d), 1, d, null));
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                if (mode == 0 && status.getText().toString().contains("搜索")) {
                    status.setText(devices.isEmpty() ? "没搜到设备：确认对方已打开「接收」" : "搜索完成，点设备后可发送");
                }
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(a)) {
                requestPeers();
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
                WifiP2pInfo info = in.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                onP2pConnection(info);
            }
        }
    };

    String safeAddr(BluetoothDevice d) {
        try { return d.getAddress(); } catch (Throwable t) { return ""; }
    }

    void addDevice(Dev d) {
        for (Dev x : devices) {
            if (x.kind == d.kind && x.addr != null && x.addr.equals(d.addr)) {
                x.name = d.name;
                x.bt = d.bt;
                x.p2p = d.p2p;
                renderDevices();
                return;
            }
        }
        devices.add(d);
        renderDevices();
    }

    /* ---------------- 发送端发现 ---------------- */

    void startBtDiscovery() {
        try {
            if (bt == null) return;
            if (bt.isDiscovering()) bt.cancelDiscovery();
            bt.startDiscovery();
        } catch (Throwable t) {
            status.setText("蓝牙搜索不可用：" + t.getClass().getSimpleName());
        }
    }

    void startWfdDiscovery() {
        if (wfd == null || wch == null) return;
        try {
            wfd.discoverPeers(wch, new WifiP2pManager.ActionListener() {
                public void onSuccess() {}
                public void onFailure(int reason) {}
            });
        } catch (Throwable ignored) {}
    }

    void requestPeers() {
        if (wfd == null || wch == null) return;
        try {
            wfd.requestPeers(wch, new WifiP2pManager.PeerListListener() {
                public void onPeersAvailable(android.net.wifi.p2p.WifiP2pDeviceList peers) {
                    if (peers == null) return;
                    for (WifiP2pDevice p : peers.getDeviceList()) {
                        addDevice(new Dev(p.deviceName, p.deviceAddress, 0, null, p));
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    /* ---------------- 接收端 ---------------- */

    void startHotspot() {
        stopHotspot();
        if (Build.VERSION.SDK_INT < 26) {
            status.setText("系统版本不支持应用内热点，改用 Wi-Fi 直连");
            createGroup();
            return;
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) { createGroup(); return; }
            status.setText("正在创建 P2P 热点…");
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    hotRes = r;
                    try {
                        WifiConfiguration c = r.getWifiConfiguration();
                        if (c != null) {
                            hotSsid = c.SSID == null ? "" : c.SSID.replace("\"", "");
                            hotPass = c.preSharedKey == null ? "" : c.preSharedKey.replace("\"", "");
                        }
                    } catch (Throwable ignored) {}
                    ui.post(new Runnable() { public void run() {
                        status.setText("P2P 热点已开 · 等待对方蓝牙握手");
                        renderReceiveHint();
                    }});
                }
                public void onStopped() { hotRes = null; hotSsid = ""; hotPass = ""; }
                public void onFailed(int reason) {
                    hotRes = null;
                    ui.post(new Runnable() { public void run() {
                        status.setText("热点创建失败（" + reason + "），改用 Wi-Fi 直连");
                        createGroup();
                    }});
                }
            }, ui);
        } catch (Throwable t) {
            createGroup();
        }
    }

    void stopHotspot() {
        try { if (hotRes != null) hotRes.close(); } catch (Throwable ignored) {}
        hotRes = null; hotSsid = ""; hotPass = "";
    }

    void startReceiver() {
        if (!serversRunning) {
            startBtServer();
            startTcpServer();
            serversRunning = true;
        }
        makeDiscoverable();
        if (p2pMode) startHotspot(); else createGroup();
        recvBtn.setText("重新准备接收");
        status.setText("接收中：让对方在「发送」里选中本机");
        renderReceiveHint();
    }

    void makeDiscoverable() {
        try {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(i);
        } catch (Throwable ignored) {}
    }

    void createGroup() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && !wm.isWifiEnabled()) {
                status.setText("Wi-Fi 未开启：请先打开 Wi-Fi，或直接用蓝牙接收");
                return;
            }
        } catch (Throwable ignored) {}
        if (wfd == null || wch == null) {
            status.setText("本机不支持 Wi-Fi 直连，将使用蓝牙接收");
            return;
        }
        try {
            wfd.createGroup(wch, new WifiP2pManager.ActionListener() {
                public void onSuccess() {
                    groupReady = true;
                    ui.post(new Runnable() { public void run() {
                        status.setText("直连热点已建立" + (localIp.isEmpty() ? "，等待对方连接…" : " · 本机 " + localIp));
                    }});
                }
                public void onFailure(int reason) {
                    try {
                        wfd.removeGroup(wch, new WifiP2pManager.ActionListener() {
                            public void onSuccess() {
                                try {
                                    wfd.createGroup(wch, new WifiP2pManager.ActionListener() {
                                        public void onSuccess() { groupReady = true; }
                                        public void onFailure(int r2) {
                                            ui.post(new Runnable() { public void run() {
                                                status.setText("Wi-Fi 直连不可用，对方可用蓝牙发送");
                                            }});
                                        }
                                    });
                                } catch (Throwable ignored) {}
                            }
                            public void onFailure(int r2) {}
                        });
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            status.setText("Wi-Fi 直连创建失败，将使用蓝牙接收");
        }
    }

    void onP2pConnection(WifiP2pInfo info) {
        if (info == null) return;
        if (info.groupFormed && info.groupOwnerAddress != null) {
            localIp = info.groupOwnerAddress.getHostAddress();
            if (mode == 1) {
                startTcpServer();
                status.setText("直连已建立 · 本机 " + localIp + " · 等待文件");
            } else if (sendPending) {
                connectAndSendTcp(info);
            }
        }
    }

    void startTcpServer() {
        if (tcpServer != null) return;
        pool.execute(new Runnable() {
            public void run() {
                try {
                    tcpServer = new ServerSocket();
                    tcpServer.setReuseAddress(true);
                    tcpServer.bind(new InetSocketAddress(PORT));
                    while (!isFinishing()) {
                        final Socket s = tcpServer.accept();
                        pool.execute(new Runnable() { public void run() { handleSocket(s, "Wi-Fi"); } });
                    }
                } catch (Throwable t) {
                    // 端口占用或已关闭
                }
            }
        });
    }

    void startBtServer() {
        if (bt == null || btServer != null) return;
        pool.execute(new Runnable() {
            public void run() {
                try {
                    btServer = bt.listenUsingRfcommWithServiceRecord(BT_NAME, APP_UUID);
                    while (!isFinishing()) {
                        final BluetoothSocket s = btServer.accept();
                        pool.execute(new Runnable() { public void run() { handleSocket(s, "蓝牙"); } });
                    }
                } catch (Throwable t) {
                    // 关闭
                }
            }
        });
    }

    /* ---------------- 发送流程 ---------------- */

    void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, 21); } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 21 && res == RESULT_OK && data != null && data.getData() != null) {
            sendUri = data.getData();
            sendName = queryName(sendUri);
            sendSize = querySize(sendUri);
            fileInfo.setText(sendName + " · " + human(sendSize));
        }
    }

    String queryName(Uri u) {
        String n = null;
        try {
            android.database.Cursor c = getContentResolver().query(u, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && idx >= 0) n = c.getString(idx);
                c.close();
            }
        } catch (Throwable ignored) {}
        if (TextUtils.isEmpty(n)) n = "文件_" + System.currentTimeMillis();
        return n;
    }

    long querySize(Uri u) {
        try {
            android.database.Cursor c = getContentResolver().query(u, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (c.moveToFirst() && idx >= 0) { long v = c.getLong(idx); c.close(); return v; }
                c.close();
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    void askText() {
        final EditText et = new EditText(this);
        et.setHint("输入要发送的文字");
        et.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle("发送文本")
                .setView(et)
                .setPositiveButton("发送", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { sendText(et.getText().toString()); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void sendText(String s) {
        if (TextUtils.isEmpty(s)) return;
        if (target == null) { Toast.makeText(this, "先选中一个设备", Toast.LENGTH_SHORT).show(); return; }
        sendPayload(target, "文本消息", 0, "text", s);
    }

    void doSend() {
        if (target == null) { Toast.makeText(this, "先选中一个设备", Toast.LENGTH_SHORT).show(); return; }
        if (sendUri == null) { Toast.makeText(this, "先选择一个文件", Toast.LENGTH_SHORT).show(); return; }
        sendPayload(target, sendName, sendSize, "file", null);
    }

    void setBusy(boolean b) {
        busy = b;
        sendBtn.setEnabled(!b);
        sendBtn.setAlpha(b ? 0.55f : 1f);
    }

    void sendPayload(Dev dev, String name, long size, String type, String text) {
        if (busy) { Toast.makeText(this, "正在传输，请稍候", Toast.LENGTH_SHORT).show(); return; }
        setBusy(true);
        sendPending = true;
        sendToken++;
        final long tok = sendToken;
        pendingName = name; pendingSize = size; pendingType = type; pendingText = text;
        status.setText("正在连接 " + dev.name + "…");
        if (dev.kind == 0) {
            connectP2p(dev);
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (sendPending && tok == sendToken) {
                        sendPending = false;
                        setBusy(false);
                        status.setText("直连超时，可改用蓝牙发送");
                    }
                }
            }, 20000);
        } else if (p2pMode && dev.bt != null) {
            connectAndHandshakeBt(dev, name, size, type, text);
        } else {
            connectAndSendBt(dev, name, size, type, text);
        }
    }

    String pendingName = "";
    long pendingSize = 0;
    String pendingType = "file";
    String pendingText = null;

    void connectP2p(final Dev dev) { connectP2p(dev, 0); }

    void connectP2p(final Dev dev, final int attempt) {
        if (wfd == null || wch == null || dev.p2p == null) { sendPending = false; status.setText("Wi-Fi 直连不可用"); return; }
        try {
            WifiP2pConfig cfg = new WifiP2pConfig();
            cfg.deviceAddress = dev.addr;
            cfg.groupOwnerIntent = 0;
            wfd.connect(wch, cfg, new WifiP2pManager.ActionListener() {
                public void onSuccess() {
                    // 连接回调可能早于 CONNECTION_CHANGED 广播，主动再查一次组信息
                    ui.postDelayed(new Runnable() { public void run() { askConnectionInfo(); } }, 2500);
                }
                public void onFailure(int reason) {
                    if (attempt < 2 && sendPending) {
                        status.setText("直连正在重试（" + (attempt + 2) + "/3）…");
                        ui.postDelayed(new Runnable() { public void run() { connectP2p(dev, attempt + 1); } }, 1800);
                    } else {
                        sendPending = false;
                        ui.post(new Runnable() { public void run() {
                            setBusy(false);
                            status.setText("直连失败（对方未点「接收」？），可改选蓝牙项发送");
                        }});
                    }
                }
            });
        } catch (Throwable t) {
            sendPending = false;
        }
    }

    void askConnectionInfo() {
        if (wfd == null || wch == null) return;
        try {
            wfd.requestConnectionInfo(wch, new WifiP2pManager.ConnectionInfoListener() {
                public void onConnectionInfoAvailable(WifiP2pInfo info) { onP2pConnection(info); }
            });
        } catch (Throwable ignored) {}
    }

    void connectAndSendTcp(final WifiP2pInfo info) {
        if (!sendPending) return;
        sendPending = false;
        final List<String> ips = new ArrayList<String>();
        try {
            if (info != null && info.groupOwnerAddress != null) {
                String o = info.groupOwnerAddress.getHostAddress();
                if (!TextUtils.isEmpty(o)) ips.add(o);
            }
        } catch (Throwable ignored) {}
        if (!ips.contains("192.168.49.1")) ips.add("192.168.49.1");
        if (!ips.contains("192.168.49.2")) ips.add("192.168.49.2");
        connectOverTcp(ips);
    }

    void connectOverTcp(final List<String> ips) {
        pool.execute(new Runnable() {
            public void run() {
                String last = "";
                for (int i = 0; i < ips.size(); i++) {
                    Socket s = null;
                    try {
                        s = new Socket();
                        s.connect(new InetSocketAddress(ips.get(i), PORT), 7000);
                        transfer(s, pendingType, pendingName, pendingSize, pendingText);
                        return;
                    } catch (Throwable t) {
                        last = t.getClass().getSimpleName();
                    } finally { close(s); }
                }
                final String msg = "网络传输失败：" + last;
                ui.post(new Runnable() { public void run() { setBusy(false); status.setText(msg); }});
            }
        });
    }

    void connectAndHandshakeBt(final Dev dev, final String name, final long size, final String type, final String text) {
        sendPending = false;
        pool.execute(new Runnable() {
            public void run() {
                BluetoothSocket s = null;
                try {
                    try { if (bt != null && bt.isDiscovering()) bt.cancelDiscovery(); } catch (Throwable ignored) {}
                    s = dev.bt.createRfcommSocketToServiceRecord(APP_UUID);
                    try {
                        s.connect();
                    } catch (Throwable t) {
                        try { s.close(); } catch (Throwable ignored) {}
                        s = fallbackSocket(dev.bt);
                        s.connect();
                    }
                    OutputStream out = outOf(s);
                    JSONObject h = new JSONObject();
                    h.put("type", "handshake");
                    h.put("from", localNameStr());
                    out.write((h.toString() + "\n").getBytes("UTF-8"));
                    out.flush();
                    InputStream in = new BufferedInputStream(inOf(s));
                    String line = readLine(in);
                    if (TextUtils.isEmpty(line)) throw new IOException("no handshake reply");
                    JSONObject r = new JSONObject(line);
                    String m = r.optString("mode", "");
                    if ("hotspot".equals(m)) {
                        final String ssid = r.optString("ssid", "");
                        final String pass = r.optString("pass", "");
                        ui.post(new Runnable() { public void run() { status.setText("握手成功，正在加入对方热点…"); }});
                        joinHotspot(ssid, pass);
                    } else {
                        ui.post(new Runnable() { public void run() { status.setText("握手成功，对方热点未就绪，改用蓝牙直传"); }});
                        connectAndSendBt(dev, name, size, type, text);
                    }
                } catch (final Throwable t) {
                    ui.post(new Runnable() { public void run() {
                        setBusy(false);
                        status.setText("P2P 握手失败：" + t.getClass().getSimpleName() + "，可改用普通蓝牙发送");
                    }});
                } finally { close(s); }
            }
        });
    }

    void joinHotspot(final String ssid, final String pass) {
        if (TextUtils.isEmpty(ssid)) {
            ui.post(new Runnable() { public void run() {
                setBusy(false);
                status.setText("对方热点信息不完整，可改用普通蓝牙发送");
            }});
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                final ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                WifiNetworkSpecifier.Builder b = new WifiNetworkSpecifier.Builder().setSsid(ssid);
                if (!TextUtils.isEmpty(pass)) b.setWpa2Passphrase(pass);
                NetworkRequest req = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .setNetworkSpecifier(b.build())
                        .build();
                hotspotCb = new ConnectivityManager.NetworkCallback() {
                    public void onAvailable(final Network n) {
                        try { cm.bindProcessToNetwork(n); } catch (Throwable ignored) {}
                        ui.post(new Runnable() { public void run() { status.setText("热点已连接，正在发送…"); }});
                        final String gw = gatewayOf(cm, n);
                        ui.postDelayed(new Runnable() { public void run() {
                            List<String> ips = new ArrayList<String>();
                            ips.add(gw);
                            if (!ips.contains("192.168.43.1")) ips.add("192.168.43.1");
                            connectOverTcp(ips);
                        }}, 1200);
                    }
                    public void onUnavailable() {
                        ui.post(new Runnable() { public void run() {
                            setBusy(false);
                            status.setText("加入热点失败，可改用普通蓝牙发送");
                        }});
                    }
                };
                cm.requestNetwork(req, hotspotCb);
                status.setText("系统会弹窗确认加入热点，请点允许");
                return;
            } catch (Throwable t) {
                // fall through to legacy attempt
            }
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiConfiguration wc = new WifiConfiguration();
            wc.SSID = "\"" + ssid + "\"";
            if (!TextUtils.isEmpty(pass)) wc.preSharedKey = "\"" + pass + "\"";
            int id = wm.addNetwork(wc);
            wm.disconnect();
            wm.enableNetwork(id, true);
            wm.reconnect();
            status.setText("正在连接热点…");
            ui.postDelayed(new Runnable() { public void run() {
                List<String> ips = new ArrayList<String>();
                ips.add("192.168.43.1");
                connectOverTcp(ips);
            }}, 4500);
        } catch (Throwable t) {
            setBusy(false);
            status.setText("连接热点失败：" + t.getClass().getSimpleName());
        }
    }

    String gatewayOf(ConnectivityManager cm, Network n) {
        try {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp != null) {
                for (RouteInfo r : lp.getRoutes()) {
                    if (r.isDefaultRoute() && r.getGateway() != null) {
                        return r.getGateway().getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "192.168.43.1";
    }

    void connectAndSendBt(final Dev dev, final String name, final long size, final String type, final String text) {
        sendPending = false;
        pool.execute(new Runnable() {
            public void run() {
                BluetoothSocket s = null;
                try {
                    try { if (bt != null && bt.isDiscovering()) bt.cancelDiscovery(); } catch (Throwable ignored) {}
                    s = dev.bt.createRfcommSocketToServiceRecord(APP_UUID);
                    try {
                        s.connect();
                    } catch (Throwable t) {
                        try { s.close(); } catch (Throwable ignored) {}
                        s = fallbackSocket(dev.bt);
                        s.connect();
                    }
                    ui.post(new Runnable() { public void run() { status.setText("已连接蓝牙，正在发送…"); }});
                    transfer(s, type, name, size, text);
                } catch (final Throwable t) {
                    ui.post(new Runnable() { public void run() {
                        setBusy(false);
                        status.setText("蓝牙连接失败：" + t.getClass().getSimpleName() + "（请确认对方已打开接收页）");
                    }});
                } finally { close(s); }
            }
        });
    }

    BluetoothSocket fallbackSocket(BluetoothDevice d) throws Exception {
        Method m = d.getClass().getMethod("createRfcommSocket", new Class[]{int.class});
        return (BluetoothSocket) m.invoke(d, Integer.valueOf(1));
    }

    /* ---------------- 传输协议 ---------------- */

    void transfer(Object s, String type, String name, long size, String text) throws Exception {
        JSONObject h = new JSONObject();
        h.put("type", type);
        h.put("name", name);
        h.put("size", size);
        h.put("text", text == null ? "" : text);
        h.put("from", localNameStr());
        OutputStream out = outOf(s);
        out.write((h.toString() + "\n").getBytes("UTF-8"));
        out.flush();
        if (!"text".equals(type)) {
            InputStream in = getContentResolver().openInputStream(sendUri);
            byte[] buf = new byte[8192];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                postProgress(done, size, true);
            }
            in.close();
        }
        out.flush();
        if (!"text".equals(type)) {
            // 等对端把缓冲区读完再关闭连接，避免蓝牙尾部数据被丢弃
            try { Thread.sleep(350); } catch (Throwable ignored) {}
            try { out.flush(); } catch (Throwable ignored) {}
        }
        ui.post(new Runnable() { public void run() {
            setBusy(false);
            progressRowSet(false);
            status.setText("发送完成 · " + ("text".equals(type) ? "文本" : human(size)));
        }});
    }

    void handleSocket(Object s, String via) {
        InputStream in = null;
        try {
            if (s instanceof Socket) ((Socket) s).setSoTimeout(60000);
            in = new BufferedInputStream(inOf(s));
            String line = readLine(in);
            if (line == null) return;
            JSONObject h = new JSONObject(line);
            final String type = h.optString("type", "file");
            final String name = h.optString("name", "文件");
            final long size = h.optLong("size", 0);
            final String text = h.optString("text", "");
            final String from = h.optString("from", "对方");
            if ("handshake".equals(type)) {
                JSONObject r = new JSONObject();
                r.put("type", "net");
                if (!TextUtils.isEmpty(hotSsid) && !TextUtils.isEmpty(hotPass)) {
                    r.put("mode", "hotspot");
                    r.put("ssid", hotSsid);
                    r.put("pass", hotPass);
                } else {
                    r.put("mode", "wfd");
                }
                OutputStream netOut = outOf(s);
                netOut.write((r.toString() + "\n").getBytes("UTF-8"));
                netOut.flush();
                return;
            }
            if ("text".equals(type)) {
                ui.post(new Runnable() { public void run() { addMessage(from, text, via); }});
                return;
            }
            Sink sink = openSink(name);
            if (sink == null) {
                ui.post(new Runnable() { public void run() { status.setText("无法写入下载目录"); }});
                return;
            }
            OutputStream out = sink.os;
            byte[] buf = new byte[8192];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                postProgress(done, size, false);
                if (size > 0 && done >= size) break;
            }
            out.flush();
            out.close();
            boolean ok = !(size > 0 && done < size);
            try {
                OutputStream ack = outOf(s);
                ack.write(ok ? 'K' : 'E');
                ack.flush();
            } catch (Throwable ignored) {}
            if (!ok) {
                sink.discard();
                ui.post(new Runnable() { public void run() { status.setText("接收中断 · " + name); }});
            } else {
                final String path = sink.label;
                final Uri gotUri = sink.uri;
                final File gotFile = sink.file;
                ui.post(new Runnable() { public void run() {
                    status.setText("接收完成 · " + name);
                    addReceived(from, name, size, path, via, gotUri, gotFile);
                    progressRowSet(false);
                }});
            }
        } catch (Throwable t) {
            ui.post(new Runnable() { public void run() { status.setText("接收失败：" + t.getClass().getSimpleName()); }});
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            close(s);
        }
    }

    InputStream inOf(Object s) throws IOException {
        if (s instanceof Socket) return ((Socket) s).getInputStream();
        return ((BluetoothSocket) s).getInputStream();
    }

    OutputStream outOf(Object s) throws IOException {
        if (s instanceof Socket) return ((Socket) s).getOutputStream();
        return ((BluetoothSocket) s).getOutputStream();
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 65536) break;
        }
        if (b.size() == 0 && c == -1) return null;
        return new String(b.toByteArray(), "UTF-8");
    }

    static class Sink {
        OutputStream os;
        String label;
        Uri uri;
        File file;
        ContentResolver cr;
        void discard() {
            try { os.close(); } catch (Throwable ignored) {}
            try {
                if (uri != null) cr.delete(uri, null, null);
                if (file != null) file.delete();
            } catch (Throwable ignored) {}
        }
    }

    Sink openSink(String name) {
        String fixed = name.replace('/', '_').replace('\\', '_');
        if (fixed.length() > 80) fixed = fixed.substring(fixed.length() - 60);
        String finalName = uniqueName(fixed);
        Sink k = new Sink();
        k.cr = getContentResolver();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, finalName);
                v.put(MediaStore.Downloads.MIME_TYPE, mimeOf(finalName));
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/快传");
                Uri u = k.cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) return null;
                k.uri = u;
                k.os = k.cr.openOutputStream(u);
                k.label = "下载/快传/" + finalName;
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "快传");
                if (!dir.exists() && !dir.mkdirs()) return null;
                k.file = new File(dir, finalName);
                k.os = new FileOutputStream(k.file);
                k.label = k.file.getAbsolutePath();
            }
            return k;
        } catch (Throwable t) {
            return null;
        }
    }

    String uniqueName(String name) {
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 0; i < 100; i++) {
            String cand = i == 0 ? name : base + " (" + i + ")" + ext;
            if (!nameExists(cand)) return cand;
        }
        return base + "_" + System.currentTimeMillis() + ext;
    }

    boolean nameExists(String n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.database.Cursor c = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Downloads._ID},
                        MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?",
                        new String[]{n, Environment.DIRECTORY_DOWNLOADS + "/快传"}, null);
                if (c != null) {
                    boolean e = c.getCount() > 0;
                    c.close();
                    return e;
                }
                return false;
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "快传");
            return new File(dir, n).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    String mimeOf(String n) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(n);
        String m = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
        return m == null ? "application/octet-stream" : m;
    }

    void postProgress(final long done, final long total, final boolean sending) {
        if (total <= 0) return;
        final int p = (int) Math.min(1000, done * 1000 / total);
        ui.post(new Runnable() { public void run() {
            progressRowSet(true);
            progBar.setProgress(p);
            progText.setText((p / 10) + "%");
            if (mode == 0 || sending) status.setText((sending ? "发送中 " : "接收中 ") + human(done) + " / " + human(total));
        }});
    }

    static String human(long b) {
        if (b <= 0) return "0 B";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    String localNameStr() {
        try { String n = bt == null ? null : bt.getName(); if (!TextUtils.isEmpty(n)) return n; } catch (Throwable ignored) {}
        return Build.MODEL == null ? "安卓设备" : Build.MODEL;
    }

    /* ---------------- 列表渲染 ---------------- */

    void renderDevices() {
        listBox.removeAllViews();
        if (devices.isEmpty()) {
            listBox.addView(hintCard("正在搜索… 让对方打开「接收」，并保持屏幕亮着"));
            return;
        }
        for (final Dev d : devices) {
            listBox.addView(deviceRow(d));
        }
    }

    View deviceRow(final Dev d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(round(d == target ? cPrimarySoft : cCard, 22, 1, d == target ? cPrimary : cLine));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        TextView icon = text(d.kind == 0 ? "W" : "B", 15, d.kind == 0 ? cPrimary : cText, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(cPrimarySoft, 14, 0, 0));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(40), dp(40));
        ilp.rightMargin = dp(12);
        icon.setLayoutParams(ilp);
        row.addView(icon);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView n = text(d.name, 15, cText, true);
        TextView s = text((d.kind == 0 ? "Wi-Fi 直连" : "蓝牙") + (d.addr == null || d.addr.isEmpty() ? "" : " · " + d.addr), 11, cSub, false);
        s.setPadding(0, dp(3), 0, 0);
        col.addView(n);
        col.addView(s);
        row.addView(col);

        View dot = new View(this);
        dot.setBackground(round(d == target ? cPrimary : Color.TRANSPARENT, 10, d == target ? 0 : 1, cLine));
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        row.addView(dot);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { target = d; renderDevices(); status.setText("已选择：" + d.name); }
        });
        return row;
    }

    View hintCard(String s) {
        TextView t = text(s, 13, cSub, false);
        t.setPadding(dp(16), dp(16), dp(16), dp(16));
        t.setBackground(round(cCard, 22, 1, cLine));
        return t;
    }

    void renderReceiveHint() {
        listBox.removeAllViews();
        listBox.addView(hintCard("点下方「接收」按钮开始等待。对方在「发送」里选中本机后，文件会直接落到 下载/快传。"));
    }

    void addReceived(String from, String name, long size, final String path, String via, final Uri uri, final File file) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(round(cCard, 22, 1, cLine));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        card.addView(text(name, 15, cText, true));
        TextView s = text(via + " · " + human(size) + " · 来自 " + from, 11, cSub, false);
        s.setPadding(0, dp(4), 0, 0);
        card.addView(s);
        TextView p = text(path, 11, cPrimary, false);
        p.setPadding(0, dp(6), 0, 0);
        card.addView(p);
        TextView act = text(uri != null ? "打开文件" : "复制路径", 12, cPrimary, true);
        act.setPadding(0, dp(8), 0, 0);
        act.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (uri != null) {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(uri, mimeOf(name));
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Throwable t) {
                        Toast.makeText(MainActivity.this, "没有能打开它的应用，文件在 " + path, Toast.LENGTH_LONG).show();
                    }
                } else {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("快传", file != null ? file.getAbsolutePath() : path));
                        Toast.makeText(MainActivity.this, "路径已复制", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                }
            }
        });
        card.addView(act);
        listBox.addView(card, 0);
        scroll.scrollTo(0, 0);
    }

    void addMessage(String from, String text, String via) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(round(cCard, 22, 1, cLine));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        card.addView(text("来自 " + from + " · " + via, 11, cSub, false));
        final String bodyText = text;
        TextView body = text(text, 15, cText, false);
        body.setPadding(0, dp(6), 0, dp(6));
        card.addView(body);
        TextView copy = text("复制", 12, cPrimary, true);
        copy.setPadding(0, dp(2), 0, 0);
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("快传", bodyText));
                } catch (Throwable ignored) {}
            }
        });
        card.addView(copy);
        listBox.addView(card, 0);
    }

    /* ---------------- 杂项 ---------------- */

    static void close(Object s) {
        try {
            if (s instanceof Socket) ((Socket) s).close();
            else if (s instanceof BluetoothSocket) ((BluetoothSocket) s).close();
        } catch (Throwable ignored) {}
    }

    static class Dev {
        String name;
        String addr;
        int kind; // 0 Wi-Fi 直连, 1 蓝牙
        BluetoothDevice bt;
        WifiP2pDevice p2p;
        Dev(String n, String a, int k, BluetoothDevice b, WifiP2pDevice p) {
            name = n == null ? "设备" : n; addr = a; kind = k; bt = b; p2p = p;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        serversRunning = false;
        stopHotspot();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.bindProcessToNetwork(null);
                if (hotspotCb != null) cm.unregisterNetworkCallback(hotspotCb);
            }
        } catch (Throwable ignored) {}
        try { unregisterReceiver(radioRx); } catch (Throwable ignored) {}
        try { if (btServer != null) btServer.close(); } catch (Throwable ignored) {}
        try { if (tcpServer != null) tcpServer.close(); } catch (Throwable ignored) {}
        try { if (bt != null) bt.cancelDiscovery(); } catch (Throwable ignored) {}
        try { if (wfd != null && wch != null) wfd.removeGroup(wch, null); } catch (Throwable ignored) {}
        pool.shutdownNow();
    }
}
