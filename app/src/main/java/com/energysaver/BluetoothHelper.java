package com.energysaver;

import static android.app.Activity.RESULT_OK;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BluetoothHelper {

    private static final String TAG = "BluetoothHelper";

    // HM-10 / HC-10 UART UUID (기존 코드 그대로)
    // HM-10 / HC-10 계열 UART
    private static final UUID UART_SERVICE_UUID =
            UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    private static final UUID UART_CHAR_UUID =
            UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 100;
    private static final int REQUEST_ENABLE_BT = 101;
    private static final long SCAN_PERIOD = 10000L;

    public interface Callback {
        void onStatusText(String text);          // 상단 상태 텍스트나 서브타이틀 변경
        void onLog(String log);                  // 로그 영역에 추가
        void onMessageReceived(String msg);      // 아두이노에서 온 데이터
        void onDeviceFound(BluetoothDevice device, String displayText);
        void onConnected();
        void onDisconnected();
    }

    private final Activity activity;
    private final Callback callback;

    // BLE 관련
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic uartCharacteristic;

    private boolean isScanning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public BluetoothHelper(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;

        BluetoothManager bluetoothManager =
                (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        if (bluetoothAdapter == null ||
                !activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {

            Toast.makeText(activity, "이 기기는 BLE를 지원하지 않습니다.", Toast.LENGTH_LONG).show();
            if (callback != null) {
                callback.onStatusText("BLE Not Supported");
                callback.onLog("BLE not supported; finishing activity.");
            }
            activity.finish();
        }
    }

    // 외부에서 권한 요청을 시작할 때 호출
    public void checkBluetoothPermissions() {
        String[] permissions;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }

        ArrayList<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(activity, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                    activity,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_BLUETOOTH_PERMISSIONS
            );
        } else {
            enableBluetooth();
        }
    }

    // Activity 의 onRequestPermissionsResult 에서 넘겨줌
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                enableBluetooth();
            } else {
                if (callback != null) {
                    callback.onStatusText("Permissions Denied");
                    callback.onLog("Bluetooth permissions denied.");
                }
                Toast.makeText(activity,
                        "Bluetooth permissions denied. Cannot use BLE.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // Activity 의 onActivityResult 에서 넘겨줌
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                if (callback != null) {
                    callback.onStatusText("Bluetooth Ready (BLE)");
                    callback.onLog("Bluetooth enabled.");
                }
            } else {
                if (callback != null) {
                    callback.onStatusText("Bluetooth Disabled");
                    callback.onLog("Bluetooth must be enabled to use BLE.");
                }
                Toast.makeText(activity,
                        "Bluetooth must be enabled to use BLE.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void enableBluetooth() {
        if (bluetoothAdapter == null) return;

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent =
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            if (callback != null) {
                callback.onStatusText("Bluetooth Ready (BLE)");
                callback.onLog("Bluetooth already enabled.");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 스캔
    // ─────────────────────────────────────────────────────────────
    public void startBleScan() {
        if (bluetoothAdapter == null) return;

        if (isScanning) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(activity,
                        Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(activity,
                    "BLUETOOTH_SCAN permission required.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            Toast.makeText(activity,
                    "BluetoothLeScanner not available",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        isScanning = true;
        if (callback != null) {
            callback.onStatusText("Scanning BLE devices...");
            callback.onLog("BLE scan started.");
        }

        handler.postDelayed(this::stopBleScan, SCAN_PERIOD);
        bluetoothLeScanner.startScan(leScanCallback);
    }

    public void stopBleScan() {
        if (!isScanning) return;

        if (bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(leScanCallback);
        }
        isScanning = false;

        if (callback != null) {
            callback.onStatusText("Scan Finished");
            callback.onLog("BLE scan finished.");
        }
    }

    private final ScanCallback leScanCallback = new ScanCallback() {

        private void handleScanResult(ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;

            String name = device.getName();
            if (name == null || name.isEmpty()) {
                name = "Unknown BLE Device";
            }
            String addr = device.getAddress();
            String deviceInfo = name + "\n" + addr;

            if (callback != null) {
                callback.onDeviceFound(device, deviceInfo);
            }
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult sr : results) {
                handleScanResult(sr);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            if (callback != null) {
                callback.onStatusText("Scan failed: " + errorCode);
                callback.onLog("BLE scan failed: " + errorCode);
            }
        }
    };

    // ─────────────────────────────────────────────────────────────
    // 연결 & UART
    // ─────────────────────────────────────────────────────────────
    public void connectToBleDevice(BluetoothDevice device) {
        if (device == null) return;

        stopBleScan();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(activity,
                        Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(activity,
                    "BLUETOOTH_CONNECT permission required.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }

        if (callback != null) {
            callback.onStatusText("Connecting to: " + device.getName());
            callback.onLog("Connecting to " + device.getName() + " / " + device.getAddress());
        }

        bluetoothGatt = device.connectGatt(activity, false, gattCallback);
    }

    public void sendCommand(String msg) {
        if (bluetoothGatt == null || uartCharacteristic == null) {
            Toast.makeText(activity,
                    "Not connected or UART not ready.",
                    Toast.LENGTH_SHORT).show();
            if (callback != null) {
                callback.onLog("Write failed: not connected or UART not ready.");
            }
            return;
        }

        uartCharacteristic.setWriteType(
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        uartCharacteristic.setValue(msg.getBytes());
        boolean ok = bluetoothGatt.writeCharacteristic(uartCharacteristic);

        if (callback != null) {
            callback.onLog("TX: \"" + msg.trim() + "\" result=" + ok);
        }

        if (!ok) {
            Toast.makeText(activity,
                    "Write failed (GATT busy?)",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt,
                                            int status,
                                            int newState) {
            super.onConnectionStateChange(gatt, status, newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (callback != null) {
                    callback.onStatusText("Discovering services...");
                    callback.onLog("Connected to GATT, discovering services...");
                }
                gatt.discoverServices();
                if (callback != null) callback.onConnected();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                uartCharacteristic = null;
                if (callback != null) {
                    callback.onStatusText("Disconnected.");
                    callback.onLog("Disconnected from GATT.");
                    callback.onDisconnected();
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt,
                                         int status) {
            super.onServicesDiscovered(gatt, status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (callback != null) {
                    callback.onStatusText("Service discovery failed: " + status);
                    callback.onLog("Service discovery failed: " + status);
                }
                return;
            }

            BluetoothGattService uartService = gatt.getService(UART_SERVICE_UUID);
            if (uartService == null) {
                if (callback != null) {
                    callback.onStatusText("UART service not found.");
                    callback.onLog("UART service not found.");
                }
                return;
            }

            uartCharacteristic = uartService.getCharacteristic(UART_CHAR_UUID);
            if (uartCharacteristic == null) {
                if (callback != null) {
                    callback.onStatusText("UART characteristic not found.");
                    callback.onLog("UART characteristic not found.");
                }
                return;
            }

            if (callback != null) {
                callback.onStatusText("Connected. UART ready.");
                callback.onLog("UART characteristic found. Enabling notifications...");
            }

            boolean notifSet = gatt.setCharacteristicNotification(uartCharacteristic, true);

            BluetoothGattDescriptor descriptor =
                    uartCharacteristic.getDescriptor(CCCD_UUID);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            if (UART_CHAR_UUID.equals(characteristic.getUuid())) {
                final String received = new String(characteristic.getValue());

                if (callback != null) {
                    callback.onLog("RX: " + received.trim());
                    callback.onMessageReceived(received);
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            if (callback != null) {
                callback.onLog("onCharacteristicWrite status=" + status);
            }
        }
    };

    // 생명주기 정리
    public void onPause() {
        stopBleScan();
    }

    public void onDestroy() {
        stopBleScan();
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }
}
