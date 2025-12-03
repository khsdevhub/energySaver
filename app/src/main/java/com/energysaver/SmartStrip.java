package com.energysaver;

import org.json.JSONException;
import org.json.JSONObject;

public class SmartStrip {

    // UI에 보이는 이름
    private String name;

    // 실제 블루투스 기기의 MAC 주소 (고유 키)
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
            // 무시하거나 로그 찍어도 됨
        }
        return obj;
    }

    public static SmartStrip fromJson(JSONObject obj) throws JSONException {
        String name = obj.optString("name", "이름 없음");
        String mac = obj.getString("mac");         // mac은 반드시 있어야 한다고 가정
        boolean on = obj.optBoolean("on", false);
        return new SmartStrip(name, mac, on);
    }

}
