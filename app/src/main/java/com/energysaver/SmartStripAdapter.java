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

    public interface OnSmartStripInteractionListener {
        void onToggle(SmartStrip strip, boolean isOn);
        void onItemLongClick(SmartStrip strip, int position, View anchorView);
    }

    private final List<SmartStrip> items;
    private final OnSmartStripInteractionListener listener;

    public SmartStripAdapter(List<SmartStrip> items,
                             OnSmartStripInteractionListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public StripViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_smart_strip, parent, false);
        return new StripViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull StripViewHolder holder, int position) {
        SmartStrip strip = items.get(position);

        holder.tvName.setText(strip.getName());
        holder.tvStatus.setText(strip.getStatusText());

        // 스위치 리스너 초기화 후 상태 반영
        holder.switchPower.setOnCheckedChangeListener(null);
        holder.switchPower.setChecked(strip.isOn());

        holder.switchPower.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (listener != null) {
                listener.onToggle(strip, isChecked);
            }
        });

        // 롱클릭 팝업 (이름 변경, 삭제)
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    listener.onItemLongClick(strip, pos, v);
                }
            }
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class StripViewHolder extends RecyclerView.ViewHolder {

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
