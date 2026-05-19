package com.seoul.watermeter;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.seoul.watermeter.USB_PERMISSION";
    private static final String[] TAB_TITLES = {"검침", "HEX 파싱", "로그"};

    public static MainActivity instance;
    public final List<MeterProtocol.ParseResult> history = new ArrayList<>();

    private UsbManager       usbManager;
    private UsbSerialPort    serialPort;
    private TextView         tvConnStatus;

    // 읽기/쓰기 스레드 분리
    private final ExecutorService readExecutor  = Executors.newSingleThreadExecutor();
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();

    private final Handler    mainHandler = new Handler(Looper.getMainLooper());
    private final Handler    autoHandler = new Handler(Looper.getMainLooper());
    private Runnable         autoRunnable;
    private volatile boolean isConnected = false;
    private volatile boolean isReading   = false;

    // 상태 enum
    enum ConnState { DISCONNECTED, CONNECTED_IDLE, CONNECTED_OK }

    private ReadFragment readFragment;
    private HexFragment  hexFragment;
    private LogFragment  logFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance      = this;
        usbManager    = (UsbManager) getSystemService(Context.USB_SERVICE);
        tvConnStatus  = findViewById(R.id.tvConnStatus);
        setupViewPager();
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        setConnState(ConnState.DISCONNECTED);
    }

    // ── 상태 표시 ─────────────────────────────────────────
    private void setConnState(ConnState state) {
        mainHandler.post(() -> {
            if (tvConnStatus == null) return;
            switch (state) {
                case DISCONNECTED:
                    tvConnStatus.setText("● 연결 안됨");
                    tvConnStatus.setTextColor(getColor(R.color.muted));
                    tvConnStatus.setBackgroundResource(R.drawable.bg_pill_gray);
                    break;
                case CONNECTED_IDLE:
                    tvConnStatus.setText("● 연결됨");
                    tvConnStatus.setTextColor(getColor(R.color.yellow));
                    tvConnStatus.setBackgroundResource(R.drawable.bg_pill_gray);
                    break;
                case CONNECTED_OK:
                    tvConnStatus.setText("● 검침 완료");
                    tvConnStatus.setTextColor(getColor(R.color.green));
                    tvConnStatus.setBackgroundResource(R.drawable.bg_pill_green);
                    break;
            }
        });
    }

    private void setupViewPager() {
        ViewPager2 vp = findViewById(R.id.viewPager);
        TabLayout  tl = findViewById(R.id.tabLayout);
        vp.setAdapter(new FragmentStateAdapter(this) {
            public int getItemCount() { return 3; }
            public Fragment createFragment(int pos) {
                switch (pos) {
                    case 0: readFragment = new ReadFragment(); return readFragment;
                    case 1: hexFragment  = new HexFragment();  return hexFragment;
                    default: logFragment = new LogFragment();  return logFragment;
                }
            }
        });
        new TabLayoutMediator(tl, vp, (tab, pos) -> tab.setText(TAB_TITLES[pos])).attach();
    }

    // ── USB 연결 ──────────────────────────────────────────
    public void connectUsb() {
        List<UsbSerialDriver> drivers =
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            addLog("USB 장치 없음", "ERR");
            toast("USB 장치를 찾을 수 없습니다");
            return;
        }
        UsbDevice device = drivers.get(0).getDevice();
        addLog("USB 감지: " + device.getProductName()
            + " VID=" + device.getVendorId()
            + " PID=" + device.getProductId(), "INFO");

        if (!usbManager.hasPermission(device)) {
            PendingIntent pi = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, pi);
            addLog("USB 권한 요청 중...", "INFO");
            return;
        }
        openPort(drivers.get(0));
    }

    private void openPort(UsbSerialDriver driver) {
        readExecutor.execute(() -> {
            try {
                UsbDeviceConnection conn =
                    usbManager.openDevice(driver.getDevice());
                if (conn == null) {
                    mainHandler.post(() -> addLog("장치 열기 실패", "ERR"));
                    return;
                }
                UsbSerialPort port = driver.getPorts().get(0);
                port.open(conn);
                port.setParameters(
                    MeterProtocol.BAUD_RATE,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                );
                port.setDTR(true);
                port.setRTS(true);
                Thread.sleep(50);

                serialPort  = port;
                isConnected = true;

                // USB 연결됨 — 아직 검침 전이므로 IDLE 상태
                setConnState(ConnState.CONNECTED_IDLE);
                mainHandler.post(() -> {
                    if (readFragment != null) readFragment.onConnected(true);
                    addLog("연결됨: " + driver.getDevice().getProductName()
                        + " (" + MeterProtocol.BAUD_RATE + " bps, 8N1)", "OK");
                });

                startReadLoop();

            } catch (IOException | InterruptedException e) {
                mainHandler.post(() -> addLog("연결 실패: " + e.getMessage(), "ERR"));
            }
        });
    }

    public void disconnectUsb() {
        stopAutoTimer();
        isReading   = false;
        isConnected = false;
        UsbSerialPort p = serialPort;
        serialPort = null;
        if (p != null) {
            try { p.close(); } catch (IOException ignored) {}
        }
        setConnState(ConnState.DISCONNECTED);
        mainHandler.post(() -> {
            if (readFragment != null) readFragment.onConnected(false);
            addLog("연결 해제", "WARN");
        });
    }

    private void startReadLoop() {
        isReading = true;
        byte[] buf    = new byte[256];
        byte[] acc    = new byte[512];
        int[]  accLen = {0};

        while (isReading && serialPort != null) {
            try {
                int n = serialPort.read(buf, 200);
                if (n > 0) {
                    System.arraycopy(buf, 0, acc, accLen[0], n);
                    accLen[0] += n;
                    int end = MeterProtocol.findLongFrameEnd(acc, accLen[0]);
                    if (end > 0) {
                        byte[] frame = new byte[end];
                        System.arraycopy(acc, 0, frame, 0, end);
                        accLen[0] -= end;
                        System.arraycopy(acc, end, acc, 0, accLen[0]);
                        MeterProtocol.ParseResult r =
                            MeterProtocol.parseLongFrame(frame);
                        mainHandler.post(() -> handleResult(r));
                    }
                    if (accLen[0] > 400) accLen[0] = 0;
                }
            } catch (IOException e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Broken pipe")
                        || msg.contains("closed"))) {
                    mainHandler.post(this::disconnectUsb);
                    break;
                }
            }
        }
    }

    public void sendRequest(int addr) {
        if (!isConnected || serialPort == null) {
            toast("먼저 연결하세요");
            return;
        }
        addLog("검침 요청 전송 중...", "INFO");
        byte[] frame = MeterProtocol.buildRequest(addr);

        writeExecutor.execute(() -> {
            try {
                serialPort.setRTS(true);
                serialPort.setDTR(true);
                Thread.sleep(35);

                byte[] flush = new byte[64];
                try { serialPort.read(flush, 30); } catch (IOException ignored) {}

                serialPort.write(frame, 3000);
                mainHandler.post(() ->
                    addLog("→ REQ_UD2 (주소 " + addr + "): "
                        + MeterProtocol.toHex(frame), "HEX")
                );
                Thread.sleep(100);

            } catch (IOException | InterruptedException e) {
                mainHandler.post(() ->
                    addLog("전송 실패: " + e.getMessage(), "ERR")
                );
            }
        });
    }

    public void setAutoInterval(int ms, int addr) {
        stopAutoTimer();
        if (ms > 0) {
            autoRunnable = () -> {
                if (isConnected) sendRequest(addr);
                autoHandler.postDelayed(autoRunnable, ms);
            };
            autoHandler.postDelayed(autoRunnable, ms);
            addLog("자동 검침: " + (ms / 1000) + "초 간격", "INFO");
        }
    }

    public void stopAutoTimer() {
        if (autoRunnable != null) {
            autoHandler.removeCallbacks(autoRunnable);
            autoRunnable = null;
        }
    }

    public void handleResult(MeterProtocol.ParseResult r) {
        if (!r.ok) { addLog("파싱 오류: " + r.error, "ERR"); return; }
        history.add(0, r);

        // 검침 성공 → 헤더 상태 "검침 완료"로 변경
        setConnState(ConnState.CONNECTED_OK);

        if (readFragment != null) readFragment.updateReading(r);
        addLog("[" + r.timestamp + "] "
            + r.meterNo + " = " + r.readingFmt() + " ㎥ | "
            + r.statusString()
            + (r.checksumOk ? "" : " | 체크섬오류"),
            r.hasWarning() ? "WARN" : "OK");
    }

    public void addLog(String msg, String level) {
        mainHandler.post(() -> {
            if (logFragment != null) logFragment.addLog(msg, level);
        });
    }

    public boolean isConnected() { return isConnected; }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                if (intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    List<UsbSerialDriver> drivers =
                        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
                    if (!drivers.isEmpty()) openPort(drivers.get(0));
                } else {
                    addLog("USB 권한 거부됨", "ERR");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                addLog("USB 장치 연결됨 — 연결 버튼을 누르세요", "INFO");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (isConnected) disconnectUsb();
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectUsb();
        readExecutor.shutdown();
        writeExecutor.shutdown();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        instance = null;
    }
}
