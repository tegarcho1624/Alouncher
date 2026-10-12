package com.alouncher.app;

import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

final class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {

    interface Listener {
        void onOpen(AppEntry app);
        void onMenu(View anchor, AppEntry app);
    }

    private final List<AppEntry> items = new ArrayList<>();
    private final Listener listener;

    AppAdapter(Listener listener) {
        this.listener = listener;
    }

    void submit(List<AppEntry> apps) {
        items.clear();
        items.addAll(apps);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AppEntry app = items.get(position);
        h.icon.setImageDrawable(app.icon);
        h.label.setText(app.label);
        h.itemView.setOnClickListener(v -> listener.onOpen(app));
        h.itemView.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.onMenu(v, app);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;

        VH(View v) {
            super(v);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
        }
    }
}
