package com.energysaver;

public class SmartStrip {

    private String name;
    private String deviceId;
    private String macAddress;
    private boolean isOn;

    public SmartStrip(String name, String deviceId, String macAddress, boolean isOn) {
        this.name = name;
        this.deviceId = deviceId;
        this.macAddress = macAddress;
        this.isOn = isOn;
    }

    public String getName() {
        return name;
    }

    public String getDeviceId() {
        return deviceId;
    }
    public String getMacAddress() {
        return macAddress;
    }

    public boolean isOn() {
        return isOn;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setOn(boolean on) {
        isOn = on;
    }
}
