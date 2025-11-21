package com.energysaver;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class SmartStripAdapter extends RecyclerView.Adapter<SmartStripAdapter.StripViewHolder> {

    public interface OnStripToggleListener {
        void onStripToggle(SmartStrip strip, boolean isOn);
    }

    private List<SmartStrip> stripList;
    private OnStripToggleListener listener;

    public SmartStripAdapter(List<SmartStrip> stripList, OnStripToggleListener listener) {
        this.stripList = stripList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public StripViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_smart_strip, parent, false);
        return new StripViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull StripViewHolder holder, int position) {
        SmartStrip strip = stripList.get(position);

        holder.tvName.setText(strip.getName());

        String statusText = "Device: " + strip.getDeviceId() +
                " · 상태: " + (strip.isOn() ? "ON" : "OFF");
        holder.tvStatus.setText(statusText);

        // 리스너 중복 호출 방지
        holder.switchPower.setOnCheckedChangeListener(null);
        holder.switchPower.setChecked(strip.isOn());

        holder.switchPower.setOnCheckedChangeListener((buttonView, isChecked) -> {
            strip.setOn(isChecked);
            if (listener != null) {
                listener.onStripToggle(strip, isChecked);
            }
        });
    }

    @Override
    public int getItemCount() {
        return stripList.size();
    }

    static class StripViewHolder extends RecyclerView.ViewHolder {

        TextView tvName;
        TextView tvStatus;
        SwitchCompat switchPower;

        public StripViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvName);
            tvStatus = itemView.findViewById(R.id.tvStatus);
            switchPower = itemView.findViewById(R.id.switchPower);
        }
    }
}
