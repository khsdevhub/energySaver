package com.energysaver;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    // ───────────────────────── UI 요소 ─────────────────────────
    private Toolbar toolbar;
    private TextView tvSubtitle;
    private RecyclerView rvDevices;
    private TextView tvAddNewDevice;
    private Button btnAllOff;
    private TextView tvLog;

    // ───────────────────── 멀티탭 리스트 관련 ───────────────────
    private static final String PREFS_NAME = "smart_strips_prefs";
    private static final String KEY_STRIPS_JSON = "strips_json";
    private final ArrayList<SmartStrip> strips = new ArrayList<>();
    private SmartStripAdapter adapter;

    // ──────────────────────── BLE / Service ─────────────────────
    private BleForegroundService bleService;
    private boolean serviceBound = false;

    private static final int REQ_BLE_PERMISSIONS = 100;
    private static final int REQ_ENABLE_BT = 101;

    // BLE 어댑터/스캐너
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private boolean isScanning = false;
    private static final long SCAN_PERIOD = 10000L; // 10초
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 스캔 결과 리스트
    private final ArrayList<BluetoothDevice> scannedDevices = new ArrayList<>();
    private final ArrayList<String> scannedDeviceInfo = new ArrayList<>();
    private ArrayAdapter<String> scanListAdapter;
    private AlertDialog scanDialog;

    // 페어링(등록) 진행 중인 기기
    private BluetoothDevice pendingPairDevice;
    private AlertDialog pairingDialog;

    // ───────────────────── 서비스 연결 콜백 ─────────────────────
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BleForegroundService.LocalBinder binder =
                    (BleForegroundService.LocalBinder) service;
            bleService = binder.getService();
            serviceBound = true;

            appendLog("BLE Service connected.");

            // 이미 등록된 기기들이 있다면 서비스에 연결 요청
            for (SmartStrip strip : strips) {
                bleService.connect(strip.getMacAddress());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            bleService = null;
            appendLog("BLE Service disconnected.");
        }
    };

    // ───────────────── BLE 서비스 브로드캐스트 수신 ──────────────
    private final BroadcastReceiver bleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("BLE_CONNECTION_STATE".equals(action)) {
                String mac = intent.getStringExtra("mac");
                String state = intent.getStringExtra("state");
                appendLog("[" + mac + "] 상태: " + state);
                tvSubtitle.setText("[" + mac + "] " + state);

                if ("READY".equals(state)) {
                    SmartStrip strip = findStripByMac(mac);
                    if (strip != null) {
                        boolean desiredOn = strip.isOn();
                        String cmd = desiredOn ? "ON\n" : "OFF\n";

                        if (serviceBound && bleService != null) {
                            bleService.sendCommand(mac, cmd);
                            appendLog("재연결 후 상태 동기화: " +
                                    strip.getName() + " → " + (desiredOn ? "ON" : "OFF"));
                        } else {
                            appendLog("재연결 후 동기화 실패(서비스 미연결): " + strip.getName());
                        }
                    } else {
                        appendLog("[" + mac + "] 는 strips 목록에 없음 (동기화 생략)");
                    }
                }
            } else if ("BLE_LOG".equals(action)) {
                String mac = intent.getStringExtra("mac");
                String log = intent.getStringExtra("log");
                appendLog("[" + mac + "] " + log);
            } else if ("BLE_MESSAGE".equals(action)) {
                String mac = intent.getStringExtra("mac");
                String msg = intent.getStringExtra("msg");
                appendLog("RX(" + mac + "): " + msg.trim());
            }
        }
    };

    // ───────────────── BOND 상태 변화 브로드캐스트 ──────────────
    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }

            BluetoothDevice device =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null || pendingPairDevice == null) return;

            if (!device.getAddress().equals(pendingPairDevice.getAddress())) return;

            int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.ERROR);
            int prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                    BluetoothDevice.ERROR);

            if (bondState == BluetoothDevice.BOND_BONDING) {
                appendLog("[" + safeName(device) + "] 페어링 진행 중...");
            } else if (bondState == BluetoothDevice.BOND_BONDED) {
                appendLog("[" + safeName(device) + "] 페어링 성공!");

                if (pairingDialog != null && pairingDialog.isShowing()) {
                    pairingDialog.dismiss();
                }

                registerAndConnectSmartStrip(pendingPairDevice);
                pendingPairDevice = null;

            } else if (bondState == BluetoothDevice.BOND_NONE &&
                    prevState == BluetoothDevice.BOND_BONDING) {
                appendLog("[" + safeName(device) + "] 페어링 실패 또는 취소.");
                if (pairingDialog != null && pairingDialog.isShowing()) {
                    pairingDialog.dismiss();
                }
                pendingPairDevice = null;
            }
        }
    };

    // ───────────────────────── onCreate ─────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        tvSubtitle = findViewById(R.id.tvSubtitle);
        rvDevices = findViewById(R.id.rvDevices);
        tvAddNewDevice = findViewById(R.id.tvAddNewDevice);
        btnAllOff = findViewById(R.id.btnAllOff);
        tvLog = findViewById(R.id.tvLog);

        BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        loadStripsFromPrefs();

        adapter = new SmartStripAdapter(strips,
                new SmartStripAdapter.OnSmartStripInteractionListener() {
                    @Override
                    public void onToggle(SmartStrip strip, boolean isOn) {
                        strip.setOn(isOn);
                        adapter.notifyDataSetChanged();

                        saveStripsToPrefs();

                        String cmd = isOn ? "ON\n" : "OFF\n";
                        if (serviceBound && bleService != null) {
                            bleService.sendCommand(strip.getMacAddress(), cmd);
                        } else {
                            appendLog("서비스 연결 안 됨, BLE 명령 전송 불가");
                        }

                        appendLog(strip.getName() + " → " + (isOn ? "ON" : "OFF")
                                + " (cmd=" + cmd.trim() + ")");
                    }

                    @Override
                    public void onItemLongClick(SmartStrip strip, int position, View anchorView) {
                        showStripContextMenu(strip, position);
                    }
                });

        rvDevices.setLayoutManager(new LinearLayoutManager(this));
        rvDevices.setAdapter(adapter);

        tvAddNewDevice.setOnClickListener(v -> startScanAndShowDialog());

        btnAllOff.setOnClickListener(v -> {
            for (SmartStrip strip : strips) {
                if (strip.isOn()) {
                    strip.setOn(false);
                }
            }
            adapter.notifyDataSetChanged();

            if (serviceBound && bleService != null) {
                for (SmartStrip strip : strips) {
                    bleService.sendCommand(strip.getMacAddress(), "OFF\n");
                }
            }
            appendLog("모든 멀티탭 OFF 명령 전송");
        });

        appendLog("앱 시작");

        startAndBindBleService();

        IntentFilter bondFilter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(bondReceiver, bondFilter);

        checkAndRequestBluetoothPermissions();
    }

    // ───────────────────── Foreground Service ───────────────────
    private void startAndBindBleService() {
        Intent intent = new Intent(this, BleForegroundService.class);

        startService(intent);

        bindService(intent, serviceConnection, BIND_AUTO_CREATE);
    }

    // ───────────────────── 로그 출력 헬퍼 ──────────────────────
    private void appendLog(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date());
        String line = "[" + time + "] " + msg + "\n";
        tvLog.append(line);
    }

    // ───────────────────── 권한 처리 ───────────────────────────
    private void checkAndRequestBluetoothPermissions() {
        ArrayList<String> perms = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!perms.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    perms.toArray(new String[0]),
                    REQ_BLE_PERMISSIONS
            );
        } else {
            ensureBluetoothEnabled();
        }
    }

    private void ensureBluetoothEnabled() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "이 기기는 Bluetooth를 지원하지 않습니다.", Toast.LENGTH_LONG).show();
            tvSubtitle.setText("Bluetooth not supported");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent =
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQ_ENABLE_BT);
        } else {
            tvSubtitle.setText("Bluetooth Ready");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLE_PERMISSIONS) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                ensureBluetoothEnabled();
            } else {
                Toast.makeText(this,
                        "Bluetooth 권한이 필요합니다.",
                        Toast.LENGTH_LONG).show();
                tvSubtitle.setText("Permissions denied");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode,
                                    int resultCode,
                                    Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                tvSubtitle.setText("Bluetooth Ready");
                appendLog("사용자가 Bluetooth를 활성화함");
            } else {
                tvSubtitle.setText("Bluetooth Disabled");
                appendLog("사용자가 Bluetooth 활성화를 거부함");
            }
        }
    }

    // ───────────────────── 새 기기 추가 / 스캔 ──────────────────
    private void startScanAndShowDialog() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth를 사용할 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            ensureBluetoothEnabled();
            return;
        }

        scannedDevices.clear();
        scannedDeviceInfo.clear();

        ListView listView = new ListView(this);
        scanListAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                scannedDeviceInfo
        );
        listView.setAdapter(scanListAdapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            BluetoothDevice device = scannedDevices.get(position);
            stopBleScan();
            addNewSmartStrip(device);
            if (scanDialog != null) {
                scanDialog.dismiss();
            }
        });

        scanDialog = new AlertDialog.Builder(this)
                .setTitle("추가할 블루투스 기기를 선택하세요")
                .setMessage("주변 BLE 기기를 검색 중입니다...")
                .setView(listView)
                .setNegativeButton("취소", (dialog, which) -> {
                    stopBleScan();
                    dialog.dismiss();
                })
                .create();

        scanDialog.show();

        appendLog("새 기기 추가 - 스캔 시작");
        startBleScan();
    }

    private void startBleScan() {
        if (isScanning) return;
        if (bluetoothAdapter == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this,
                        "BLUETOOTH_SCAN 권한이 필요합니다.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            Toast.makeText(this,
                    "BluetoothLeScanner를 사용할 수 없습니다.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        isScanning = true;
        tvSubtitle.setText("BLE 기기 검색 중...");
        appendLog("BLE 스캔 시작");

        handler.postDelayed(this::stopBleScan, SCAN_PERIOD);
        bluetoothLeScanner.startScan(leScanCallback);
    }

    private void stopBleScan() {
        if (!isScanning) return;
        if (bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(leScanCallback);
        }
        isScanning = false;
        tvSubtitle.setText("스캔 완료");
        appendLog("BLE 스캔 종료");
    }

    private final ScanCallback leScanCallback = new ScanCallback() {
        private void handleResult(ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;

            String name = device.getName();
            if (name == null || name.isEmpty()) {
                name = "알 수 없는 기기";
            }
            String addr = device.getAddress();
            String info = name + "\n" + addr;

            handleScannedDevice(device, info);
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleResult(result);
        }

        @Override
        public void onBatchScanResults(java.util.List<ScanResult> results) {
            for (ScanResult sr : results) {
                handleResult(sr);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            appendLog("BLE 스캔 실패: " + errorCode);
            tvSubtitle.setText("스캔 실패: " + errorCode);
        }
    };

    private void handleScannedDevice(BluetoothDevice device, String displayText) {
        if (device == null) return;
        String mac = device.getAddress();
        if (mac == null) return;

        if (isRegisteredDevice(mac)) {
            appendLog("이미 등록된 기기 스킵: " + displayText);
            return;
        }

        for (BluetoothDevice d : scannedDevices) {
            if (mac.equals(d.getAddress())) {
                return;
            }
        }

        scannedDevices.add(device);
        scannedDeviceInfo.add(displayText);

        if (scanListAdapter != null) {
            scanListAdapter.notifyDataSetChanged();
        }
    }

    // ───────────────────── 기기 등록 / 페어링 ───────────────────
    private void addNewSmartStrip(BluetoothDevice device) {
        if (device == null) {
            Toast.makeText(this, "잘못된 기기입니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        String mac = device.getAddress();
        if (mac == null) {
            Toast.makeText(this, "잘못된 기기입니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isRegisteredDevice(mac)) {
            Toast.makeText(this, "이미 등록된 기기입니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
            registerAndConnectSmartStrip(device);
        } else {
            showPairingPopup(device);
        }
    }

    private boolean isRegisteredDevice(String macAddress) {
        for (SmartStrip strip : strips) {
            if (strip.getMacAddress().equalsIgnoreCase(macAddress)) {
                return true;
            }
        }
        return false;
    }

    private void showPairingPopup(BluetoothDevice device) {
        String name = safeName(device);
        String mac = device.getAddress();

        String message = "기기: " + name + "\n"
                + "MAC: " + mac + "\n\n"
                + "이 기기를 앱에서 등록(페어링)하시겠습니까?";

        new AlertDialog.Builder(this)
                .setTitle("블루투스 기기 등록")
                .setMessage(message)
                .setPositiveButton("등록", (dialog, which) -> startBonding(device))
                .setNegativeButton("취소", (dialog, which) -> {
                    appendLog("사용자가 페어링 취소: " + name + " (" + mac + ")");
                    dialog.dismiss();
                })
                .show();
    }

    private void startBonding(BluetoothDevice device) {
        if (device == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this,
                        "BLUETOOTH_CONNECT 권한이 필요합니다.",
                        Toast.LENGTH_SHORT).show();
                appendLog("BLUETOOTH_CONNECT 권한 없음으로 페어링 요청 실패");
                return;
            }
        }

        pendingPairDevice = device;

        pairingDialog = new AlertDialog.Builder(this)
                .setTitle("블루투스 페어링")
                .setMessage("기기와 페어링 중입니다...\n\n" +
                        "필요하면 나타나는 시스템 팝업에서\n" +
                        "페어링을 승인해 주세요.")
                .setCancelable(false)
                .setNegativeButton("취소", (dialog, which) -> {
                    appendLog("사용자가 페어링 진행 중 취소를 눌렀습니다.");
                    pendingPairDevice = null;
                    dialog.dismiss();
                })
                .show();

        appendLog("[" + safeName(device) + "] 에 대해 createBond() 호출");

        boolean started = device.createBond();

        if (!started) {
            appendLog("createBond() 호출 실패");
            Toast.makeText(this, "페어링을 시작할 수 없습니다.", Toast.LENGTH_SHORT).show();
            if (pairingDialog != null && pairingDialog.isShowing()) {
                pairingDialog.dismiss();
            }
            pendingPairDevice = null;
        }
    }

    private void registerAndConnectSmartStrip(BluetoothDevice device) {
        String mac = device.getAddress();
        if (mac == null) return;

        if (isRegisteredDevice(mac)) {
            appendLog("이미 등록된 기기이므로 추가하지 않음: " + mac);
            return;
        }

        String name = device.getName();
        if (name == null || name.trim().isEmpty()) {
            String macSuffix = mac.length() >= 5 ? mac.substring(mac.length() - 5) : mac;
            name = "멀티탭 (" + macSuffix + ")";
        }

        SmartStrip newStrip = new SmartStrip(name, mac, false);
        strips.add(newStrip);
        adapter.notifyItemInserted(strips.size() - 1);

        saveStripsToPrefs();

        appendLog("새 기기 등록: " + name + " / " + mac);
        Toast.makeText(this, "새 멀티탭 추가: " + name, Toast.LENGTH_SHORT).show();

        if (serviceBound && bleService != null) {
            bleService.connect(mac);
        } else {
            appendLog("서비스가 아직 연결되지 않아 나중에 자동 연결 필요");
        }
    }

    private String safeName(BluetoothDevice device) {
        if (device == null) return "알 수 없는 기기";
        String name = device.getName();
        if (name == null || name.trim().isEmpty()) {
            return "알 수 없는 기기";
        }
        return name;
    }

    // ─────────────────── 롱클릭 메뉴: 이름 변경 / 삭제 ─────────────
    private void showStripContextMenu(SmartStrip strip, int position) {
        String[] items = {"이름 변경", "삭제", "취소"};

        new AlertDialog.Builder(this)
                .setTitle(strip.getName())
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: // 이름 변경
                            showRenameDialog(strip, position);
                            break;
                        case 1: // 삭제
                            removeStrip(position);
                            break;
                        case 2: // 취소
                        default:
                            dialog.dismiss();
                            break;
                    }
                })
                .show();
    }

    private void showRenameDialog(SmartStrip strip, int position) {
        final EditText editText = new EditText(this);
        editText.setText(strip.getName());
        editText.setSelection(strip.getName().length());

        new AlertDialog.Builder(this)
                .setTitle("이름 변경")
                .setView(editText)
                .setPositiveButton("저장", (dialog, which) -> {
                    String newName = editText.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        strip.setName(newName);
                        adapter.notifyItemChanged(position);
                        appendLog("이름 변경: " + newName);

                        saveStripsToPrefs();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void removeStrip(int position) {
        if (position < 0 || position >= strips.size()) return;

        SmartStrip removed = strips.get(position);
        strips.remove(position);
        adapter.notifyItemRemoved(position);

        saveStripsToPrefs();

        appendLog("멀티탭 삭제: " + removed.getName() + " (" + removed.getMacAddress() + ")");
        Toast.makeText(this, "삭제됨: " + removed.getName(), Toast.LENGTH_SHORT).show();

        if (serviceBound && bleService != null) {
            bleService.disconnect(removed.getMacAddress());
        }
    }

    // ───────────────────── 기기 저장 관리 ─────────────────────
    private void loadStripsFromPrefs() {
        strips.clear();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString(KEY_STRIPS_JSON, null);
        if (json == null || json.isEmpty()) {
            appendLog("저장된 멀티탭 목록 없음");
            return;
        }

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                SmartStrip strip = SmartStrip.fromJson(obj);
                strips.add(strip);
            }
            appendLog("저장된 멀티탭 " + strips.size() + "개 불러옴");
        } catch (JSONException e) {
            appendLog("멀티탭 목록 로드 중 오류: " + e.getMessage());
        }
    }
    private void saveStripsToPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        for (SmartStrip strip : strips) {
            arr.put(strip.toJson());
        }
        prefs.edit().putString(KEY_STRIPS_JSON, arr.toString()).apply();
        appendLog("멀티탭 목록 저장 (" + strips.size() + "개)");
    }


    // ───────────────────── 생명주기 처리 ───────────────────────
    @Override
    protected void onResume() {
        super.onResume();

        IntentFilter f = new IntentFilter();
        f.addAction("BLE_CONNECTION_STATE");
        f.addAction("BLE_LOG");
        f.addAction("BLE_MESSAGE");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bleReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bleReceiver, f);
        }
    }


    private SmartStrip findStripByMac(String mac) {
        if (mac == null) return null;
        for (SmartStrip strip : strips) {
            if (mac.equalsIgnoreCase(strip.getMacAddress())) {
                return strip;
            }
        }
        return null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(bleReceiver);
        } catch (IllegalArgumentException e) {
            // 이미 해제된 경우 등 무시
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        stopBleScan();

        try {
            unregisterReceiver(bondReceiver);
        } catch (IllegalArgumentException e) {
            // 이미 해제된 경우 등 무시
        }

        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }
}
