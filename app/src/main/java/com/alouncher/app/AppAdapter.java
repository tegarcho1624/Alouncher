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
        void onHiddenApps();
    }

    private static final int TYPE_APP = 0;
    private static final int TYPE_HIDDEN = 1;

    private final List<AppEntry> items = new ArrayList<>();
    private boolean showHiddenCell;
    private final Listener listener;

    AppAdapter(Listener listener) {
        this.listener = listener;
    }

    void submit(List<AppEntry> apps, boolean showHiddenCell) {
        items.clear();
        items.addAll(apps);
        this.showHiddenCell = showHiddenCell;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return position < items.size() ? TYPE_APP : TYPE_HIDDEN;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        if (position >= items.size()) {
            // Celda especial al final del grid
            h.icon.setImageResource(R.drawable.ic_hidden_cell);
            h.label.setText(R.string.apps_ocultas);
            h.itemView.setOnClickListener(v -> listener.onHiddenApps());
            h.itemView.setOnLongClickListener(null);
            h.itemView.setLongClickable(false);
            return;
        }
        AppEntry app = items.get(position);
        h.icon.setImageDrawable(app.icon());
        h.label.setText(app.label());
        h.itemView.setOnClickListener(v -> listener.onOpen(app));
        h.itemView.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.onMenu(v, app);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size() + (showHiddenCell ? 1 : 0);
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
