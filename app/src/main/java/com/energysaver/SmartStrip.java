package com.energysaver;

import org.json.JSONException;
import org.json.JSONObject;

public class SmartStrip {

    private String name;

    private String macAddress;

    private boolean isOn;

    public SmartStrip(String name, String macAddress, boolean isOn) {
        this.name = name;
        this.macAddress = macAddress;
        this.isOn = isOn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {   // 이름 변경용
        this.name = name;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public boolean isOn() {
        return isOn;
    }

    public void setOn(boolean on) {
        isOn = on;
    }

    public String getStatusText() {
        return "MAC: " + macAddress + " · 상태: " + (isOn ? "ON" : "OFF");
    }
    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("name", name);
            obj.put("mac", macAddress);
            obj.put("on", isOn);
        } catch (JSONException e) {
            // 무시
        }
        return obj;
    }

    public static SmartStrip fromJson(JSONObject obj) throws JSONException {
        String name = obj.optString("name", "이름 없음");
        String mac = obj.getString("mac");
        boolean on = obj.optBoolean("on", false);
        return new SmartStrip(name, mac, on);
    }

}
