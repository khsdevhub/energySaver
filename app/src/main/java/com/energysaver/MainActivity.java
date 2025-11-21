package com.energysaver;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.app.AlertDialog;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class MainActivity extends AppCompatActivity {

    private RecyclerView rvDevices;
    private Button btnAllOff;
    private TextView tvLog;
    private ScrollView scrollLog;

    private SmartStripAdapter adapter;
    private List<SmartStrip> stripList = new ArrayList<>();

    private BluetoothHelper bluetoothHelper;
    private static final int REQ_BLUETOOTH_PERMISSIONS = 1001;

    private static final String PREF_NAME = "my_smart_power_prefs";
    private static final String KEY_STRIPS = "key_strips";
    private Set<String> pendingCommands = new HashSet<>();  // 요청 처리 중인 deviceId 목록

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rvDevices = findViewById(R.id.rvDevices);
        btnAllOff = findViewById(R.id.btnAllOff);
        tvLog = findViewById(R.id.tvLog);
        scrollLog = findViewById(R.id.scrollLog);

        bluetoothHelper = new BluetoothHelper(this);
        checkBluetoothPermissions();

        stripList = new ArrayList<>();
        loadStrips();

        if (stripList.isEmpty()) {
            stripList.add(new SmartStrip(
                    "책상 스탠드 멀티탭",
                    "STRIP_1",
                    "00:00:00:00:00:01",
                    true
            ));
            stripList.add(new SmartStrip(
                    "PC 본체 멀티탭",
                    "STRIP_2",
                    "00:00:00:00:00:02",
                    false
            ));
            stripList.add(new SmartStrip(
                    "공기청정기 멀티탭",
                    "STRIP_3",
                    "00:00:00:00:00:03",
                    true
            ));
            saveStrips();
        }

        adapter = new SmartStripAdapter(
                stripList,
                new SmartStripAdapter.OnStripActionListener() {
                    @Override
                    public void onStripToggle(SmartStrip strip, boolean isOn) {
                        String deviceId = strip.getDeviceId();

                        if (pendingCommands.contains(deviceId)) {
                            appendLog(strip.getName() + " → 이전 요청 처리 중, 새 요청은 무시됩니다.");

                            strip.setOn(!isOn);
                            adapter.notifyDataSetChanged();
                            return;
                        }
                        pendingCommands.add(deviceId);

                        String cmd = isOn ? "ON" : "OFF";
                        appendLog(strip.getName() + " → " + cmd + " 요청");

                        new Thread(() -> {
                            boolean ok = bluetoothHelper.sendCommandToMac(strip.getMacAddress(), cmd);

                            runOnUiThread(() -> {
                                pendingCommands.remove(deviceId);

                                if (ok) {
                                    appendLog(strip.getName() + " → " + cmd
                                            + " 전송 결과: 성공(또는 시도됨)");
                                    saveStrips();
                                } else {
                                    appendLog(strip.getName() + " → " + cmd
                                            + " 전송 결과: 실패, 상태를 되돌립니다.");
                                    strip.setOn(!isOn);
                                    adapter.notifyDataSetChanged();
                                }
                            });
                        }).start();
                    }

                    @Override
                    public void onStripDeleted(SmartStrip strip) {
                        appendLog(strip.getName() + " 기기 삭제됨");
                        saveStrips();
                    }

                    @Override
                    public void onStripRenamed(SmartStrip strip, String oldName, String newName) {
                        appendLog("기기 이름 변경: \"" + oldName + "\" → \"" + newName + "\"");
                        saveStrips();
                    }
                }
        );

        rvDevices.setLayoutManager(new LinearLayoutManager(this));
        rvDevices.setAdapter(adapter);

        btnAllOff.setOnClickListener(v -> {
            appendLog("모든 멀티탭 OFF 요청");

            for (SmartStrip strip : stripList) {
                strip.setOn(false);
            }
            adapter.notifyDataSetChanged();
            saveStrips();

            new Thread(() -> {
                for (SmartStrip strip : stripList) {
                    boolean ok = bluetoothHelper.sendCommandToMac(strip.getMacAddress(), "OFF");
                    boolean finalOk = ok;
                    runOnUiThread(() ->
                            appendLog(strip.getName() + " → OFF 전송 결과: "
                                    + (finalOk ? "성공(또는 시도됨)" : "실패")));
                }
            }).start();
        });
        TextView tvAddNew = findViewById(R.id.tvAddNewDevice);

// 밑줄 강제 적용
        tvAddNew.setPaintFlags(tvAddNew.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);

// 클릭 이벤트
        tvAddNew.setOnClickListener(v -> {
            appendLog("새 기기 추가하기 클릭됨 → 블루투스 기기 목록 열기 준비");

            // TODO: 기기 스캔 화면 또는 다이얼로그로 이동
            openBluetoothDeviceChooser();
        });
    }
    private void openBluetoothDeviceChooser() {

        // 블루투스 어댑터 (이름을 btAdapter로!)
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            appendLog("블루투스를 지원하지 않는 기기입니다.");
            return;
        }

        if (!btAdapter.isEnabled()) {
            appendLog("블루투스가 꺼져 있습니다. 먼저 켜주세요.");
            return;
        }

        // 페어링된 기기 목록
        Set<BluetoothDevice> paired = btAdapter.getBondedDevices();

        if (paired.isEmpty()) {
            appendLog("페어링된 블루투스 기기가 없습니다.");
            return;
        }

        // 이름/주소 리스트 생성
        List<String> deviceNames = new ArrayList<>();
        List<String> deviceMacs = new ArrayList<>();

        for (BluetoothDevice d : paired) {
            deviceNames.add(d.getName() + " (" + d.getAddress() + ")");
            deviceMacs.add(d.getAddress());
        }

        String[] items = deviceNames.toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle("추가할 기기를 선택하세요")
                .setItems(items, (dialog, which) -> {

                    String chosenMac = deviceMacs.get(which);
                    String chosenName = deviceNames.get(which);

                    appendLog("선택된 기기: " + chosenName);

                    // 새 SmartStrip 만들어서 리스트에 추가
                    SmartStrip newStrip = new SmartStrip(
                            "새 멀티탭 " + (stripList.size() + 1),
                            "STRIP_" + (stripList.size() + 1),
                            chosenMac,
                            false
                    );

                    stripList.add(newStrip);

                    // 여기 adapter는 MainActivity의 필드 (SmartStripAdapter)를 가리킴
                    adapter.notifyItemInserted(stripList.size() - 1);
                    saveStrips(); // 저장

                    appendLog("새 기기 추가됨: " + newStrip.getName());
                })
                .show();
    }



    private void appendLog(String message) {
        String current = tvLog.getText().toString();
        String newLog = "· " + message + "\n";
        tvLog.setText(current + newLog);
        scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (!BluetoothAdapter.getDefaultAdapter().isEnabled()) {
                appendLog("블루투스가 꺼져 있습니다.");
            }
            return;
        }

        boolean needConnect = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED;
        boolean needScan = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED;

        if (needConnect || needScan) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                    },
                    REQ_BLUETOOTH_PERMISSIONS
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_BLUETOOTH_PERMISSIONS) {
            boolean granted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            appendLog("블루투스 권한 요청 결과: " + (granted ? "허용됨" : "거부됨"));
        }
    }

    private void saveStrips() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        Gson gson = new Gson();
        String json = gson.toJson(stripList);
        prefs.edit().putString(KEY_STRIPS, json).apply();
    }

    private void loadStrips() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String json = prefs.getString(KEY_STRIPS, null);
        if (json != null) {
            Gson gson = new Gson();
            java.lang.reflect.Type type =
                    new TypeToken<List<SmartStrip>>() {}.getType();
            List<SmartStrip> loaded = gson.fromJson(json, type);
            stripList.clear();
            stripList.addAll(loaded);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveStrips();
    }
}

