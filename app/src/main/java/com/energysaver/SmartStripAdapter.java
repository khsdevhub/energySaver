package com.energysaver;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class SmartStripAdapter extends RecyclerView.Adapter<SmartStripAdapter.StripViewHolder> {

    public interface OnStripActionListener {
        void onStripToggle(SmartStrip strip, boolean isOn);
        void onStripDeleted(SmartStrip strip);
        void onStripRenamed(SmartStrip strip, String oldName, String newName);
    }

    private List<SmartStrip> stripList;
    private OnStripActionListener actionListener;

    public SmartStripAdapter(List<SmartStrip> stripList,
                             OnStripActionListener actionListener) {
        this.stripList = stripList;
        this.actionListener = actionListener;
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

        String statusText = "Device: " + strip.getDeviceId()
                + " · 상태: " + (strip.isOn() ? "ON" : "OFF");
        holder.tvStatus.setText(statusText);

        holder.switchPower.setOnCheckedChangeListener(null);
        holder.switchPower.setChecked(strip.isOn());
        holder.switchPower.setOnCheckedChangeListener((buttonView, isChecked) -> {
            strip.setOn(isChecked);

            View card = holder.itemView;
            card.animate()
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .setDuration(80)
                    .withEndAction(() ->
                            card.animate()
                                    .scaleX(1.0f)
                                    .scaleY(1.0f)
                                    .setDuration(100)
                                    .start()
                    ).start();

            if (actionListener != null) {
                actionListener.onStripToggle(strip, isChecked);
            }

            String newStatusText = "Device: " + strip.getDeviceId()
                    + " · 상태: " + (strip.isOn() ? "ON" : "OFF");
            holder.tvStatus.setText(newStatusText);
        });

        holder.itemView.setOnLongClickListener(v -> {
            v.animate()
                    .scaleX(0.94f)
                    .scaleY(0.94f)
                    .alpha(0.6f)
                    .setDuration(100)
                    .withEndAction(() -> {
                        v.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .alpha(1.0f)
                                .setDuration(120)
                                .start();

                        Context ctx = v.getContext();
                        String[] items = new String[]{"이름 변경", "기기 삭제", "취소"};

                        new AlertDialog.Builder(ctx)
                                .setTitle(strip.getName())
                                .setItems(items, (dialog, which) -> {
                                    if (which == 0) {
                                        showRenameDialog(ctx, holder, strip);
                                    } else if (which == 1) {
                                        showDeleteDialog(ctx, holder, strip);
                                    } else {
                                        dialog.dismiss();
                                    }
                                })
                                .show();
                    })
                    .start();
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return stripList.size();
    }

    private void showRenameDialog(Context ctx, StripViewHolder holder, SmartStrip strip) {
        final EditText input = new EditText(ctx);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(strip.getName());
        input.setSelection(strip.getName().length());
        int padding = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);

        new AlertDialog.Builder(ctx)
                .setTitle("기기 이름 변경")
                .setView(input)
                .setPositiveButton("저장", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        String oldName = strip.getName();
                        strip.setName(newName);

                        int pos = holder.getAdapterPosition();
                        if (pos != RecyclerView.NO_POSITION) {
                            notifyItemChanged(pos);
                        }

                        if (actionListener != null) {
                            actionListener.onStripRenamed(strip, oldName, newName);
                        }
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showDeleteDialog(Context ctx, StripViewHolder holder, SmartStrip strip) {
        new AlertDialog.Builder(ctx)
                .setTitle("기기 삭제")
                .setMessage(strip.getName() + " 기기를 목록에서 삭제할까요?")
                .setPositiveButton("삭제", (dialog, which) -> {
                    int currentPos = holder.getAdapterPosition();
                    if (currentPos != RecyclerView.NO_POSITION) {
                        SmartStrip removed = stripList.remove(currentPos);
                        notifyItemRemoved(currentPos);
                        if (actionListener != null) {
                            actionListener.onStripDeleted(removed);
                        }
                    }
                })
                .setNegativeButton("취소", null)
                .show();
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
