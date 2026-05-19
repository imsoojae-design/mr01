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

    private UsbManager      usbManager;
    private UsbSerialPort   serialPort;
    private ExecutorService executor;
    private final Handler   mainHandler  = new Handler(Looper.getMainLooper());
    private final Handler   autoHandler  = new Handler(Looper.getMainLooper());
    private Runnable        autoRunnable;
    private volatile boolean isConnected = false;
    private volatile boolean isReading   = false;

    // Fragment refs
    private ReadFragment  readFragment;
    private HexFragment   hexFragment;
    private LogFragment   logFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance    = this;
        usbManager  = (UsbManager) getSystemService(Context.USB_SERVICE);
        executor    = Executors.newSingleThreadExecutor();

        setupViewPager();

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    // ── ViewPager 설정 ────────────────────────────────────
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
            addLog("USB 장치 없음 — OTG 케이블 확인", "ERR");
            toast("USB 장치를 찾을 수 없습니다");
            return;
        }

        UsbSerialDriver driver = drivers.get(0);
        UsbDevice       device = driver.getDevice();
        addLog("USB 감지: " + device.getProductName()
            + " VID=" + device.getVendorId()
            + " PID=" + device.getProductId(), "INFO");

        if (!usbManager.hasPermission(device)) {
            PendingIntent pi = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, pi);
            addLog("USB 권한 요청 중...", "INFO");
            return;
        }
        openPort(driver);
    }

    // ── 포트 열기 ─────────────────────────────────────────
    private void openPort(UsbSerialDriver driver) {
        executor.execute(() -> {
            try {
                UsbDeviceConnection conn = usbManager.openDevice(driver.getDevice());
                if (conn == null) {
                    mainHandler.post(() -> addLog("장치 열기 실패 (권한 없음)", "ERR"));
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

                // 포트 안정화 대기
                Thread.sleep(200);

                serialPort  = port;
                isConnected = true;

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

    // ── 연결 해제 ─────────────────────────────────────────
    public void disconnectUsb() {
        stopAutoTimer();
        isReading   = false;
        isConnected = false;

        UsbSerialPort p = serialPort;
        serialPort = null;
        if (p != null) {
            try { p.close(); } catch (IOException ignored) {}
        }

        mainHandler.post(() -> {
            if (readFragment != null) readFragment.onConnected(false);
            addLog("연결 해제", "WARN");
        });
    }

    // ── 수신 루프 ─────────────────────────────────────────
    private void startReadLoop() {
        isReading = true;
        byte[]   buf    = new byte[256];
        byte[]   acc    = new byte[512];
        int[]    accLen = {0};

        // 수신 루프는 openPort executor에서 계속 실행
        while (isReading && serialPort != null) {
            try {
                int n = serialPort.read(buf, 150);
                if (n > 0) {
                    System.arraycopy(buf, 0, acc, accLen[0], n);
                    accLen[0] += n;

                    int end = MeterProtocol.findLongFrameEnd(acc, accLen[0]);
                    if (end > 0) {
                        byte[] frame = new byte[end];
                        System.arraycopy(acc, 0, frame, 0, end);
                        accLen[0] -= end;
                        System.arraycopy(acc, end, acc, 0, accLen[0]);

                        MeterProtocol.ParseResult result =
                            MeterProtocol.parseLongFrame(frame);
                        mainHandler.post(() -> handleResult(result));
                    }
                    if (accLen[0] > 400) accLen[0] = 0;
                }
            } catch (IOException e) {
                if (isReading) mainHandler.post(this::disconnectUsb);
                break;
            }
        }
    }

    // ── 검침 요청 전송 ─────────────────────────────────────
    public void sendRequest(int addr) {
        if (!isConnected || serialPort == null) {
            toast("먼저 연결하세요");
            return;
        }

        byte[] frame = MeterProtocol.buildRequest(addr);

        executor.execute(() -> {
            try {
                // 수신 버퍼 비우기
                byte[] flush = new byte[64];
                try { serialPort.read(flush, 50); } catch (IOException ignored) {}

                // 전송
                serialPort.write(frame, 3000);

                mainHandler.post(() ->
                    addLog("→ REQ_UD2 (주소 " + addr + "): "
                        + MeterProtocol.toHex(frame), "HEX")
                );
            } catch (IOException e) {
                mainHandler.post(() ->
                    addLog("전송 실패: " + e.getMessage(), "ERR")
                );
            }
        });
    }

    // ── 자동 반복 ─────────────────────────────────────────
    public void setAutoInterval(int ms, int addr) {
        stopAutoTimer();
        if (ms > 0) {
            autoRunnable = () -> {
                if (isConnected) sendRequest(addr);
                autoHandler.postDelayed(autoRunnable, ms);
            };
            autoHandler.postDelayed(autoRunnable, ms);
            addLog("자동 검침: " + (ms / 1000) + "초 간격 시작", "INFO");
        }
    }

    public void stopAutoTimer() {
        if (autoRunnable != null) {
            autoHandler.removeCallbacks(autoRunnable);
            autoRunnable = null;
        }
    }

    // ── 결과 처리 ─────────────────────────────────────────
    public void handleResult(MeterProtocol.ParseResult r) {
        if (!r.ok) {
            addLog("파싱 오류: " + r.error, "ERR");
            return;
        }
        history.add(0, r);
        if (readFragment != null) readFragment.updateReading(r);
        addLog("[" + r.timestamp + "] "
            + r.meterNo + " = " + r.readingFmt() + " ㎥ | "
            + r.statusString()
            + (r.checksumOk ? "" : " | 체크섬오류"),
            r.hasWarning() ? "WARN" : "OK");
    }

    // ── 로그 ─────────────────────────────────────────────
    public void addLog(String msg, String level) {
        mainHandler.post(() -> {
            if (logFragment != null) logFragment.addLog(msg, level);
        });
    }

    public boolean isConnected() { return isConnected; }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    // ── USB BroadcastReceiver ─────────────────────────────
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    List<UsbSerialDriver> drivers =
                        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
                    if (!drivers.isEmpty()) openPort(drivers.get(0));
                    else addLog("권한 획득했으나 드라이버 없음", "ERR");
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
        executor.shutdown();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        instance = null;
    }
}
