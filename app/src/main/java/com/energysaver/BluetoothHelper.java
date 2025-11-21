package com.energysaver;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

public class BluetoothHelper {

    private static final String TAG = "BluetoothHelper";

    // HC-06 같은 SPP 모듈이 사용하는 공용 UUID
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;

    public BluetoothHelper(Context context) {
        this.context = context;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public boolean isBluetoothSupported() {
        return bluetoothAdapter != null;
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // S(API 31) 미만은 별도 CONNECT 권한 없음
            return true;
        }
        int perm = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT);
        return perm == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 해당 MAC 주소의 기기에 문자열 명령 한 번 보내고 소켓 닫기
     * (새 쓰레드에서 호출)
     */
    public boolean sendCommandToMac(String macAddress, String command) {
        if (!isBluetoothSupported()) {
            Log.e(TAG, "Bluetooth not supported on this device");
            return false;
        }
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is disabled");
            return false;
        }
        if (!hasConnectPermission()) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted");
            return false;
        }
        if (macAddress == null || macAddress.isEmpty()) {
            Log.e(TAG, "MAC address is empty");
            return false;
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);

        BluetoothSocket socket = null;
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            bluetoothAdapter.cancelDiscovery(); // 연결 전에 검색 중지

            socket.connect(); // 여기서 실제 연결 시도

            OutputStream out = socket.getOutputStream();
            String payload = command + "\n";
            out.write(payload.getBytes());
            out.flush();

            Log.d(TAG, "Command sent: " + payload.trim() + " to " + macAddress);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error in sendCommandToMac", e);
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
        }
    }
}
