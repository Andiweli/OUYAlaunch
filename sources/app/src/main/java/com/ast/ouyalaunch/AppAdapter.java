package com.ast.ouyalaunch;

import android.content.Context;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

public class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {
    // Match the original unfocused tile size; enlarging every tile narrows the visible gaps.
    private static final float NORMAL_SCALE = 1.0f;
    private static final float FOCUS_SCALE = 1.1f;
    private final LayoutInflater inflater;
    private final OnAppClickListener listener;
    private final IconLoader icons = new IconLoader();
    private final Set<VH> holders = Collections.newSetFromMap(new WeakHashMap<VH, Boolean>());
    private List<AppEntry> data = new ArrayList<>();
    private String selectedPackage;
    private boolean imagesActive = true;
    private int prefetchFirst = Integer.MIN_VALUE, prefetchDirection;

    public interface OnAppClickListener {
        void onAppLaunch(AppEntry entry);
        void onGenreChange(AppEntry entry);
        void onToggleFavorite(AppEntry entry);
    }

    public AppAdapter(Context context, OnAppClickListener listener) {
        inflater = LayoutInflater.from(context);
        this.listener = listener;
    }

    /** Independent value snapshots are essential: MainActivity edits mutable AppEntry values. */
    public void setData(List<AppEntry> entries) {
        final List<AppEntry> old = data;
        final List<AppEntry> next = DataStore.snapshot(entries);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int a, int b) {
                return old.get(a).packageName.equals(next.get(b).packageName);
            }
            @Override public boolean areContentsTheSame(int a, int b) {
                AppEntry x = old.get(a), y = next.get(b);
                // Genre/favorite are not rendered; click handlers resolve the current entry.
                return same(x.title, y.title) && same(x.iconPath, y.iconPath)
                        && x.packageUpdatedAt == y.packageUpdatedAt
                        && x.iconCacheVersion == y.iconCacheVersion;
            }
        }, false);
        prefetchFirst = Integer.MIN_VALUE;
        data = next;
        diff.dispatchUpdatesTo(this);
    }

    /** Switching whole pages does not need a quadratic diff of unrelated game lists. */
    public void replacePage(List<AppEntry> entries) {
        data = DataStore.snapshot(entries);
        selectedPackage = null;
        prefetchFirst = Integer.MIN_VALUE;
        notifyDataSetChanged();
    }

    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        VH holder = new VH(inflater.inflate(R.layout.item_app, parent, false));
        holders.add(holder);
        holder.itemView.setOnFocusChangeListener((view, focused) -> {
            if (focused) {
                AppEntry entry = entryFor(holder);
                if (entry != null) selectedPackage = entry.packageName;
            }
            view.animate().cancel();
            // An overlay border avoids background-padding/layout changes on every focus move.
            holder.border.setAlpha(focused ? 1f : 0f);
            view.animate().scaleX(focused ? FOCUS_SCALE : NORMAL_SCALE)
                    .scaleY(focused ? FOCUS_SCALE : NORMAL_SCALE)
                    .setDuration(focused ? 120 : 150).start();
        });
        holder.itemView.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_BUTTON_A && key != KeyEvent.KEYCODE_DPAD_CENTER
                    && key != KeyEvent.KEYCODE_BUTTON_X && key != KeyEvent.KEYCODE_BUTTON_B) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                AppEntry entry = entryFor(holder);
                if (entry != null && listener != null) {
                    if (key == KeyEvent.KEYCODE_BUTTON_X) listener.onGenreChange(entry);
                    else if (key == KeyEvent.KEYCODE_BUTTON_B) listener.onToggleFavorite(entry);
                    else listener.onAppLaunch(entry);
                }
            }
            return true; // Consume UP too; never also synthesize a second click.
        });
        holder.itemView.setOnClickListener(view -> {
            AppEntry entry = entryFor(holder);
            if (entry != null && listener != null) listener.onAppLaunch(entry);
        });
        return holder;
    }

    private AppEntry entryFor(VH holder) {
        int position = holder.getAdapterPosition();
        return position >= 0 && position < data.size() ? data.get(position) : null;
    }

    @Override public void onBindViewHolder(@NonNull VH holder, int position) {
        AppEntry entry = data.get(position);
        holder.title.setText(entry.title);
        holder.path = entry.iconPath;
        holder.itemView.animate().cancel();
        boolean focused = holder.itemView.hasFocus();
        if (focused) selectedPackage = entry.packageName;
        holder.border.setAlpha(focused ? 1f : 0f);
        holder.itemView.setScaleX(focused ? FOCUS_SCALE : NORMAL_SCALE);
        holder.itemView.setScaleY(focused ? FOCUS_SCALE : NORMAL_SCALE);
        // Cache hits also bind immediately while paused; misses wait for the worker to resume.
        icons.load(holder.img, holder.path);
    }

    @Override public void onViewAttachedToWindow(@NonNull VH holder) {
        super.onViewAttachedToWindow(holder);
        if (imagesActive) icons.load(holder.img, holder.path);
    }

    @Override public void onViewRecycled(@NonNull VH holder) {
        holder.itemView.animate().cancel();
        holder.border.setAlpha(0f);
        holder.itemView.setScaleX(NORMAL_SCALE);
        holder.itemView.setScaleY(NORMAL_SCALE);
        IconLoader.clear(holder.img);
        holder.path = null;
        super.onViewRecycled(holder);
    }

    @Override public int getItemCount() { return data.size(); }
    public String getSelectedPackage() { return selectedPackage; }
    public int positionOf(String pkg) {
        if (pkg != null) for (int i = 0; i < data.size(); i++) {
            if (pkg.equals(data.get(i).packageName)) return i;
        }
        return RecyclerView.NO_POSITION;
    }
    public AppEntry getSelectedApp() {
        int position = positionOf(selectedPackage);
        return position >= 0 ? data.get(position) : null;
    }
    public void prefetch(int first, int direction) {
        if (first == prefetchFirst && direction == prefetchDirection) return;
        prefetchFirst = first;
        prefetchDirection = direction;
        icons.prefetch(data, first, direction);
    }
    public void prefetchPage(List<AppEntry> entries, int first, int count) {
        icons.prefetchPage(entries, first, count);
    }
    public void cancelPrefetch() {
        prefetchFirst = Integer.MIN_VALUE;
        icons.cancelPrefetch();
    }
    public void invalidateIcons() {
        prefetchFirst = Integer.MIN_VALUE;
        icons.invalidate();
        for (VH holder : holders) IconLoader.clear(holder.img);
    }
    public void preparePage(List<AppEntry> entries, int first, int count, Runnable ready) {
        icons.prepare(entries, first, count, ready);
    }
    public void cancelPreparation() { icons.cancelPreparation(); }
    public void setImagesActive(boolean active) { setImagesActive(active, true); }

    // A resumed page is prepared as a unit before binding. Do not start decoding
    // the old attached page first when a different destination was pending at stop.
    void setImagesActive(boolean active, boolean reloadAttached) {
        imagesActive = active;
        icons.setPaused(!active);
        if (active) {
            prefetchFirst = Integer.MIN_VALUE;
            if (reloadAttached) reloadAttachedImages();
        } else for (VH holder : holders) {
            holder.itemView.animate().cancel();
            boolean focused = holder.itemView.hasFocus();
            holder.itemView.setScaleX(focused ? FOCUS_SCALE : NORMAL_SCALE);
            holder.itemView.setScaleY(focused ? FOCUS_SCALE : NORMAL_SCALE);
            holder.border.setAlpha(focused ? 1f : 0f);
        }
    }
    private void reloadAttachedImages() {
        if (!imagesActive) return;
        for (VH holder : holders) {
            if (holder.itemView.getParent() != null && holder.path != null) icons.load(holder.img, holder.path);
        }
    }
    /** Release attached, cached and pooled holders without losing their data/focus. */
    public void releaseUiMemory() {
        setImagesActive(false);
        prefetchFirst = Integer.MIN_VALUE;
        icons.releaseUiMemory();
        // The weak registry includes holders outside recycler.getChildCount(),
        // especially RecyclerView's offscreen cache and its recycled-view pool.
        for (VH holder : holders) IconLoader.clear(holder.img);
    }

    public void trimMemory(boolean severe) { icons.trim(severe); }
    public void close() {
        icons.close();
        for (VH holder : holders) IconLoader.clear(holder.img);
        holders.clear();
    }

    public static class VH extends RecyclerView.ViewHolder {
        final ImageView img;
        final TextView title;
        final View border;
        String path;
        VH(View view) {
            super(view);
            img = view.findViewById(R.id.img);
            title = view.findViewById(R.id.title);
            border = view.findViewById(R.id.focus_border);
        }
    }
}
