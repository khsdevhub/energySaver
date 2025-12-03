package com.energysaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BleForegroundService extends Service {

    public static final String TAG = "BleForegroundService";

    private static final UUID UART_SERVICE_UUID =
            UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    private static final UUID UART_CHAR_UUID =
            UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private static final String CHANNEL_ID = "ble_foreground_channel";
    private static final int NOTIFICATION_ID = 1001;

    private final IBinder binder = new LocalBinder();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Map<String, DeviceConnection> connections = new HashMap<>();

    private BroadcastReceiver bluetoothStateReceiver;

    public class LocalBinder extends Binder {
        public BleForegroundService getService() {
            return BleForegroundService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }


    private final long HEARTBEAT_INTERVAL_MS = 5000;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        registerBluetoothStateReceiver();

        startHeartbeat();
    }

    private void startHeartbeat() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<String, DeviceConnection> entry : connections.entrySet()) {
                    String mac = entry.getKey();
                    DeviceConnection dc = entry.getValue();
                    if (dc.isReady()) {
                        dc.send("PING\n");
                    }
                }
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        }, HEARTBEAT_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");

        if (bluetoothStateReceiver != null) {
            try {
                unregisterReceiver(bluetoothStateReceiver);
            } catch (IllegalArgumentException ignored) {}
        }

        for (DeviceConnection dc : connections.values()) {
            dc.close();
        }
        connections.clear();
    }


    private void registerBluetoothStateReceiver() {
        bluetoothStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    int state = intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR
                    );

                    if (state == BluetoothAdapter.STATE_ON) {
                        Log.d(TAG, "Bluetooth STATE_ON -> reconnect all devices");
                        onBluetoothTurnedOn();
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(bluetoothStateReceiver, filter);
    }

    private void onBluetoothTurnedOn() {
        for (Map.Entry<String, DeviceConnection> entry : connections.entrySet()) {
            String mac = entry.getKey();
            DeviceConnection dc = entry.getValue();

            if (!dc.userRequestedClose) {
                broadcastLog(mac, "Bluetooth ON → reconnect");
                dc.connect();
            }
        }
    }

    // ───────────────────── Foreground 알림 설정 ─────────────────────

    private void startForegroundInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "EnergySaver BLE Control",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }

        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("EnergySaver BLE 연결 유지 중")
                .setContentText("스마트 멀티탭과의 Bluetooth 연결을 유지하고 있습니다.")
                .setSmallIcon(R.drawable.ic_launcher_foreground) // 프로젝트 아이콘으로 교체 가능
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    // ───────────────────── 외부에서 사용할 공개 API ─────────────────────

    public void connect(String macAddress) {
        if (macAddress == null) return;
        Log.d(TAG, "connect() requested for mac=" + macAddress);

        DeviceConnection dc = connections.get(macAddress);
        if (dc == null) {
            dc = new DeviceConnection(macAddress);
            connections.put(macAddress, dc);
        }
        dc.connect();
    }

    public void sendCommand(String macAddress, String msg) {
        if (macAddress == null || msg == null) return;

        DeviceConnection dc = connections.get(macAddress);
        if (dc != null) {
            dc.send(msg);
        } else {
            Log.w(TAG, "sendCommand: no connection object for mac=" + macAddress);
            broadcastLog(macAddress,
                    "sendCommand 호출됨, 하지만 아직 DeviceConnection 없음.");
        }
    }

    public void disconnect(String macAddress) {
        if (macAddress == null) return;

        DeviceConnection dc = connections.get(macAddress);
        if (dc != null) {
            dc.manualClose();
        }
    }

    public boolean isConnected(String macAddress) {
        DeviceConnection dc = connections.get(macAddress);
        return dc != null && dc.isReady();
    }

    // ───────────────────── 기기별 연결 관리 클래스 ─────────────────────

    private class DeviceConnection extends BluetoothGattCallback {

        private final String mac;
        private BluetoothGatt gatt;
        private BluetoothGattCharacteristic uartChar;

        private boolean userRequestedClose = false;

        // 재연결 관련
        private int reconnectAttempts = 0;
        private final int maxReconnectAttempts = 5;
        private final long baseDelayMs = 2000;           // 2초
        private final long maxDelayMs  = 30000;          // 최대 30초

        DeviceConnection(String mac) {
            this.mac = mac;
        }

        boolean isReady() {
            return (gatt != null && uartChar != null);
        }

        void connect() {
            Log.d(TAG, "DeviceConnection.connect() mac=" + mac);
            userRequestedClose = false;
            reconnectAttempts = 0;
            startConnect();
        }

        private void startConnect() {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                Log.w(TAG, "BluetoothAdapter not ready for connect: " + mac);
                broadcastLog(mac, "BluetoothAdapter not ready, 연결 불가");
                return;
            }
            BluetoothDevice device;
            try {
                device = adapter.getRemoteDevice(mac);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "getRemoteDevice 실패 mac=" + mac, e);
                broadcastLog(mac, "getRemoteDevice 실패: " + e.getMessage());
                return;
            }

            if (device == null) {
                Log.w(TAG, "No BluetoothDevice for mac=" + mac);
                broadcastLog(mac, "mac에 해당하는 BluetoothDevice 없음");
                return;
            }

            Log.d(TAG, "connectGatt() mac=" + mac);

            if (gatt != null) {
                gatt.close();
                gatt = null;
            }

            // 실제 연결 시작
            gatt = device.connectGatt(BleForegroundService.this, false, this);
            broadcastState(mac, "CONNECTING");
        }

        void manualClose() {
            Log.d(TAG, "manualClose() mac=" + mac);
            userRequestedClose = true;
            reconnectAttempts = 0;
            close();
            broadcastState(mac, "DISCONNECTED");
        }

        void close() {
            if (gatt != null) {
                try {
                    gatt.close();
                } catch (Exception ignored) {
                }
                gatt = null;
            }
            uartChar = null;
        }

        void send(String msg) {
            if (gatt == null || uartChar == null) {
                Log.w(TAG, "send: not ready for mac=" + mac);
                broadcastLog(mac, "Write failed: not connected or UART not ready.");
                return;
            }

            uartChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            uartChar.setValue(msg.getBytes());
            boolean ok = gatt.writeCharacteristic(uartChar);

            broadcastLog(mac, "TX: \"" + msg.trim() + "\" result=" + ok);

            if (!ok) {
                Log.w(TAG, "writeCharacteristic failed for mac=" + mac);
            }
        }

        // ───────────── BluetoothGattCallback 구현 ─────────────

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt,
                                            int status,
                                            int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            Log.d(TAG, "onConnectionStateChange mac=" + mac
                    + " status=" + status
                    + " newState=" + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                broadcastLog(mac, "Connected to GATT. Discovering services...");
                broadcastState(mac, "CONNECTED");
                if (!gatt.discoverServices()) {
                    broadcastLog(mac, "discoverServices() 실패");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                uartChar = null;
                broadcastLog(mac, "Disconnected from GATT server.");
                broadcastState(mac, "DISCONNECTED");

                if (!userRequestedClose) {
                    scheduleReconnect();
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            Log.d(TAG, "onServicesDiscovered mac=" + mac + " status=" + status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                broadcastLog(mac, "Service discovery failed: " + status);
                return;
            }

            BluetoothGattService uartService = gatt.getService(UART_SERVICE_UUID);
            if (uartService == null) {
                broadcastLog(mac, "UART service not found.");
                return;
            }

            uartChar = uartService.getCharacteristic(UART_CHAR_UUID);
            if (uartChar == null) {
                broadcastLog(mac, "UART characteristic not found.");
                return;
            }

            boolean notifSet = gatt.setCharacteristicNotification(uartChar, true);
            broadcastLog(mac, "setCharacteristicNotification: " + notifSet);

            BluetoothGattDescriptor descriptor = uartChar.getDescriptor(CCCD_UUID);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean descWrite = gatt.writeDescriptor(descriptor);
                broadcastLog(mac, "writeDescriptor(CCCD): " + descWrite);
            } else {
                broadcastLog(mac, "CCCD descriptor not found. Notifications may not work.");
            }

            reconnectAttempts = 0;
            broadcastState(mac, "READY");
            broadcastLog(mac, "UART ready for " + mac);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            if (UART_CHAR_UUID.equals(characteristic.getUuid())) {
                String received = new String(characteristic.getValue());
                Log.d(TAG, "onCharacteristicChanged mac=" + mac + " msg=" + received);
                broadcastMessage(mac, received);
            }
        }

        // ───────────── 자동 재연결 스케줄링 ─────────────

        private void scheduleReconnect() {
            if (userRequestedClose) {
                return;
            }

            reconnectAttempts++;

            long delay = baseDelayMs * reconnectAttempts;
            if (delay > maxDelayMs) {
                delay = maxDelayMs;
            }

            broadcastLog(mac, "Schedule reconnect #" + reconnectAttempts +
                    " in " + (delay / 1000f) + "s");

            mainHandler.postDelayed(this::startConnect, delay);
        }

    }

    // ───────────────────── MainActivity에 알리는 Broadcast들 ─────────────────────

    private void broadcastState(String mac, String state) {
        Intent i = new Intent("BLE_CONNECTION_STATE");
        i.putExtra("mac", mac);
        i.putExtra("state", state);
        sendBroadcast(i);
    }

    private void broadcastLog(String mac, String log) {
        Intent i = new Intent("BLE_LOG");
        i.putExtra("mac", mac);
        i.putExtra("log", log);
        sendBroadcast(i);
    }

    private void broadcastMessage(String mac, String msg) {
        Intent i = new Intent("BLE_MESSAGE");
        i.putExtra("mac", mac);
        i.putExtra("msg", msg);
        sendBroadcast(i);
    }
}
