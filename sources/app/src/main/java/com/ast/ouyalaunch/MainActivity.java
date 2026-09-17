package com.ast.ouyalaunch;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.MarginLayoutParamsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements AppAdapter.OnAppClickListener {
    public static final List<String> GENRES = Collections.unmodifiableList(Arrays.asList(
            "Favorites", "Casual", "Action", "Racing", "Simulation", "RPG", "Apps"));
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int[] WIFI_ICONS = { R.drawable.ic_wifi_0, R.drawable.ic_wifi_1,
            R.drawable.ic_wifi_2, R.drawable.ic_wifi_3, R.drawable.ic_wifi_4 };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable networkUpdate = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            updateNetworkIcon();
            handler.postDelayed(this, 15000L);
        }
    };
    private RecyclerView recycler;
    private GridLayoutManager grid;
    private View overlayLoading;
    private SlidingTabLayout tabContainer;
    private android.view.ViewTreeObserver.OnPreDrawListener pageReveal;
    private ImageView imgNetworkStatus;
    private AppAdapter adapter;
    private DataStore dataStore;
    private List<AppEntry> allApps = new ArrayList<>();
    private final List<List<AppEntry>> tabLists = new ArrayList<>();
    private final boolean[] tabDirty = new boolean[GENRES.size()];
    private final Parcelable[] tabScroll = new Parcelable[GENRES.size()];
    private final String[] tabFocus = new String[GENRES.size()];
    private final int[] tabFirstVisible = new int[GENRES.size()];
    private CategorySettings categories = CategorySettings.defaults();
    private androidx.appcompat.app.AlertDialog categoryDialog;
    private boolean preparingPage;
    private int requestedTab = -1;
    private int pageGeneration;
    private boolean stoppedSincePause = true;
    private long pausedAt;
    private boolean showCategoriesOnResume;
    private int currentTab;
    private int focusGeneration;
    private int networkIcon;
    private boolean scanInProgress;
    private boolean loaded;
    private boolean destroyed;
    private boolean resumed;
    private boolean launchPending;
    private boolean l2Pressed, r2Pressed, yPressed;
    private long yDownAt;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        recycler = findViewById(R.id.recycler);
        overlayLoading = findViewById(R.id.overlay_loading);
        tabContainer = findViewById(R.id.tab_container);
        imgNetworkStatus = findViewById(R.id.imgNetworkStatus);
        imgNetworkStatus.setColorFilter(Color.parseColor("#D8D9DC"), PorterDuff.Mode.SRC_IN);
        dataStore = new DataStore(this);
        for (int i = 0; i < GENRES.size(); i++) {
            tabLists.add(new ArrayList<>());
            tabDirty[i] = true;
            if (state != null) {
                tabScroll[i] = state.getParcelable("scroll" + i);
                tabFocus[i] = state.getString("focus" + i);
                tabFirstVisible[i] = state.getInt("first" + i, 0);
            }
        }
        if (state != null) currentTab = Math.max(0, Math.min(GENRES.size() - 1, state.getInt("tab", 0)));
        grid = new ControllerGridLayoutManager(this, 3);
        recycler.setPreserveFocusAfterLayout(false);
        recycler.setLayoutManager(grid);
        recycler.setHasFixedSize(true);
        recycler.setItemAnimator(null);
        // Keep a few rows, not 30 bitmap-holding tile views. Tune on real hardware if needed.
        recycler.setItemViewCacheSize(12);
        recycler.addItemDecoration(new SpacingDecoration(getResources().getDimensionPixelSize(R.dimen.grid_spacing_px)));
        adapter = new AppAdapter(this, this);
        recycler.setAdapter(adapter);
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                if (!resumed || scanInProgress || preparingPage) return;
                if (dy < 0) adapter.prefetch(grid.findFirstVisibleItemPosition() - 1, -1);
                else adapter.prefetch(grid.findLastVisibleItemPosition() + 1, 1);
            }
        });
        recycler.post(() -> ensureGridFitsFixedTiles(grid));
        setupTabs();
        startLoading(false);
        View root = findViewById(android.R.id.content);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(250).start();
    }

    private void setupTabs() {
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < GENRES.size(); i++) {
            final int index = i;
            TextView title = (TextView) inflater.inflate(android.R.layout.simple_list_item_1, tabContainer, false);
            title.setText(categories.name(i));
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setMaxWidth(Math.round(240 * getResources().getDisplayMetrics().density));
            title.setVisibility(categories.enabled(i) ? View.VISIBLE : View.GONE);
            title.setTextAppearance(this, R.style.TabText);
            title.setPadding(32, 0, 32, 0);
            title.setOnClickListener(view -> selectTab(index));
            title.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
            tabContainer.addView(title);
        }
        findViewById(R.id.header).setOnLongClickListener(view -> { showCategorySettings(); return true; });
        updateTabBackgrounds();
    }

    private void updateTabBackgrounds() {
        int highlighted = requestedTab >= 0 ? requestedTab : currentTab;
        for (int i = 0; i < tabContainer.getChildCount(); i++) {
            View view = tabContainer.getChildAt(i);
            view.setSelected(i == highlighted);
        }
        tabContainer.select(highlighted);
        keepSelectedTabVisible();
    }

    private void keepSelectedTabVisible() {
        View selected = tabContainer.getChildAt(requestedTab >= 0 ? requestedTab : currentTab);
        if (selected != null) selected.post(() -> {
            if (!destroyed && selected.getVisibility() == View.VISIBLE) {
                selected.requestRectangleOnScreen(new android.graphics.Rect(0, 0,
                        selected.getWidth(), selected.getHeight()), true);
            }
        });
    }

    private void rememberTab() {
        if (preparingPage) return;
        tabScroll[currentTab] = grid.onSaveInstanceState();
        tabFirstVisible[currentTab] = Math.max(0, grid.findFirstVisibleItemPosition());
        String selected = adapter.getSelectedPackage();
        if (adapter.positionOf(selected) >= 0) tabFocus[currentTab] = selected;
    }

    private void selectTab(int index) {
        if (scanInProgress || index < 0 || index >= GENRES.size() || !categories.enabled(index)) return;
        if (index == currentTab) {
            if (preparingPage) {
                pageGeneration++;
                preparingPage = false;
                requestedTab = -1;
                adapter.cancelPreparation();
                cancelPageReveal();
                recycler.animate().cancel();
                recycler.setAlpha(1f);
                updateTabBackgrounds();
                restoreFocus(tabFocus[currentTab], tabFirstVisible[currentTab]);
            }
            return;
        }
        rememberTab();
        presentPage(index, false);
    }

    private void presentPage(int index, boolean initial) {
        ++focusGeneration;
        recycler.stopScroll();
        final int ticket = ++pageGeneration;
        preparingPage = true;
        requestedTab = index;
        cancelPageReveal();
        recycler.animate().cancel();
        recycler.setAlpha(0f);
        updateTabBackgrounds();
        // Let the hidden grid and moving header reach the screen before preparing the page.
        recycler.postOnAnimation(() -> preparePage(index, initial, ticket));
    }

    private void preparePage(int index, boolean initial, int ticket) {
        if (destroyed || ticket != pageGeneration) return;
        final List<AppEntry> entries = new ArrayList<>(entriesForTab(index));
        int span = grid.getSpanCount();
        int first = Math.max(0, Math.min(tabFirstVisible[index], entries.size() - 1));
        first = first / span * span;
        int height = recycler.getHeight();
        if (height <= 0) height = getResources().getDisplayMetrics().heightPixels
                - getResources().getDimensionPixelSize(R.dimen.header_height_px);
        int row = getResources().getDimensionPixelSize(R.dimen.tile_height_px)
                + getResources().getDimensionPixelSize(R.dimen.grid_spacing_px);
        int count = Math.min(24, ((height + row - 1) / row + 1) * span);
        adapter.preparePage(entries, first, count, () -> {
            if (destroyed || ticket != pageGeneration) return;
            if (!initial && loaded) rememberTab();
            currentTab = index;
            recycler.clearFocus();
            adapter.replacePage(entries);
            updateTabBackgrounds();
            if (tabScroll[index] != null) grid.onRestoreInstanceState(tabScroll[index]);
            else grid.scrollToPositionWithOffset(0, 0);
            restoreFocus(tabFocus[index], 0);
            revealPageAfterLayout(ticket);
        });
    }

    private void cancelPageReveal() {
        if (pageReveal != null) {
            recycler.getViewTreeObserver().removeOnPreDrawListener(pageReveal);
            pageReveal = null;
        }
    }

    private void revealPageAfterLayout(int ticket) {
        cancelPageReveal();
        pageReveal = () -> {
            if (destroyed || ticket != pageGeneration) { cancelPageReveal(); return true; }
            if (recycler.hasPendingAdapterUpdates() || recycler.isLayoutRequested()) return true;
            cancelPageReveal();
            preparingPage = false;
            requestedTab = -1;
            overlayLoading.setVisibility(View.GONE);
            recycler.animate().alpha(1f).setDuration(300)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).start();
            return true;
        };
        recycler.getViewTreeObserver().addOnPreDrawListener(pageReveal);
        recycler.invalidate();
    }

    private List<AppEntry> currentEntries() { return entriesForTab(currentTab); }

    private List<AppEntry> entriesForTab(int tab) {
        if (!tabDirty[tab]) return tabLists.get(tab);
        List<AppEntry> result = tabLists.get(tab);
        result.clear();
        String genre = GENRES.get(tab);
        for (AppEntry entry : allApps) {
            if (tab == 0 ? entry.favorite : genre.equals(entry.genre)) result.add(entry);
        }
        final Map<String, String> keys = new HashMap<>();
        for (AppEntry entry : result) keys.put(entry.packageName,
                (entry.title == null ? "" : entry.title).toLowerCase(Locale.ROOT));
        Collections.sort(result, (a, b) -> keys.get(a.packageName).compareTo(keys.get(b.packageName)));
        tabDirty[tab] = false;
        return result;
    }

    private void dirtyGenre(String genre) {
        int index = GENRES.indexOf(genre);
        if (index >= 0) tabDirty[index] = true;
    }

    private void restoreFocus(String pkg, int fallback) {
        final int generation = ++focusGeneration;
        int found = adapter.positionOf(pkg);
        final int position = found >= 0 ? found : Math.max(0, Math.min(fallback, adapter.getItemCount() - 1));
        recycler.post(() -> {
            if (destroyed || generation != focusGeneration) return;
            if (adapter.getItemCount() == 0) {
                tabContainer.getChildAt(currentTab).requestFocus();
                return;
            }
            RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(position);
            if (holder != null) holder.itemView.requestFocus();
            else {
                grid.scrollToPosition(position);
                recycler.post(() -> {
                    if (destroyed || generation != focusGeneration) return;
                    RecyclerView.ViewHolder next = recycler.findViewHolderForAdapterPosition(position);
                    if (next != null) next.itemView.requestFocus();
                });
            }
            if (resumed) adapter.prefetch(grid.findLastVisibleItemPosition() + 1, 1);
        });
    }

    private void refreshEntries(String preferred, int fallback) {
        adapter.setData(currentEntries());
        restoreFocus(preferred, fallback);
    }

    private AppEntry findEntry(AppEntry requested) {
        if (requested != null) for (AppEntry entry : allApps) {
            if (entry.packageName.equals(requested.packageName)) return entry;
        }
        return null;
    }

    private void startLoading(boolean manualScan) {
        if (scanInProgress) return;
        pageGeneration++;
        preparingPage = false;
        requestedTab = -1;
        adapter.cancelPreparation();
        scanInProgress = true;
        if (loaded) rememberTab();
        overlayLoading.setVisibility(View.VISIBLE);
        adapter.setImagesActive(false);
        enqueueLoad(this, manualScan, loaded ? DataStore.snapshot(allApps) : null);
    }

    /** Static worker holds application context and a weak UI reference, never an Activity. */
    private static void enqueueLoad(MainActivity activity, boolean manualScan, List<AppEntry> snapshot) {
        Context context = activity.getApplicationContext();
        DataStore store = activity.dataStore;
        WeakReference<MainActivity> owner = new WeakReference<>(activity);
        DataStore.execute(() -> {
            try {
                final CategorySettings preferences = CategorySettings.load(context);
                List<AppEntry> entries = snapshot != null ? snapshot : store.load();
                if (manualScan || entries == null) {
                    if (entries == null) entries = new ArrayList<>();
                    entries = AppScanner.buildCache(context, entries);
                    store.save(entries);
                }
                final List<AppEntry> result = entries;
                MAIN.post(() -> {
                    MainActivity target = owner.get();
                    if (target == null || target.destroyed) return;
                    target.allApps = result;
                    Arrays.fill(target.tabDirty, true);
                    target.categories = preferences;
                    if (!preferences.enabled(target.currentTab)) target.currentTab = preferences.firstVisible();
                    target.updateCategoryTabs();
                    target.loaded = true;
                    target.scanInProgress = false;
                    if (manualScan) target.adapter.invalidateIcons();
                    target.adapter.setImagesActive(target.resumed);
                    target.presentPage(target.currentTab, true);
                });
            } catch (Exception error) {
                Log.e("OUYAlaunch", "Could not load/scan apps", error);
                MAIN.post(() -> {
                    MainActivity target = owner.get();
                    if (target == null || target.destroyed) return;
                    target.scanInProgress = false;
                    target.overlayLoading.setVisibility(View.GONE);
                    target.adapter.setImagesActive(target.resumed);
                    Toast.makeText(target, R.string.load_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void saveApps() { enqueueSave(this, DataStore.snapshot(allApps)); }
    private static void enqueueSave(MainActivity activity, List<AppEntry> snapshot) {
        DataStore store = activity.dataStore;
        WeakReference<MainActivity> owner = new WeakReference<>(activity);
        DataStore.execute(() -> {
            try { store.save(snapshot); }
            catch (Exception error) {
                MAIN.post(() -> {
                    MainActivity target = owner.get();
                    if (target != null && !target.destroyed) {
                        Toast.makeText(target, R.string.save_failed, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private static void refreshGenreNames(MainActivity activity) {
        Context context = activity.getApplicationContext();
        WeakReference<MainActivity> owner = new WeakReference<>(activity);
        DataStore.execute(() -> {
            CategorySettings settings = CategorySettings.load(context);
            MAIN.post(() -> {
                MainActivity target = owner.get();
                if (target != null && !target.destroyed) target.applyCategories(settings);
            });
        });
    }

    private void updateCategoryTabs() {
        for (int i = 0; i < GENRES.size(); i++) {
            TextView title = (TextView) tabContainer.getChildAt(i);
            title.setText(categories.name(i));
            title.setVisibility(categories.enabled(i) ? View.VISIBLE : View.GONE);
        }
        updateTabBackgrounds();
    }

    private void applyCategories(CategorySettings settings) {
        categories = settings;
        updateCategoryTabs();
        int target = requestedTab >= 0 ? requestedTab : currentTab;
        if (!categories.enabled(target)) {
            target = categories.enabled(currentTab) ? currentTab : categories.firstVisible();
            if (loaded && !scanInProgress) presentPage(target, false);
            else currentTab = target;
        }
    }

    private void showCategorySettings() {
        if (!loaded || scanInProgress || preparingPage || destroyed) return;
        if (categoryDialog != null && categoryDialog.isShowing()) return;
        resetButtons();
        categoryDialog = CategoryDialog.show(this, categories, settings -> {
            if (!destroyed) applyCategories(settings);
        });
        categoryDialog.setOnDismissListener(dialog -> { categoryDialog = null; resetButtons(); });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Some OUYA firmware sends the middle button to HOME instead of delivering a key.
        // Only handle a HOME re-entry from the already visible launcher, never return from a game.
        if (intent != null && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_HOME)
                && (resumed || (!stoppedSincePause && SystemClock.uptimeMillis() - pausedAt < 1500))) {
            showCategoriesOnResume = true;
            if (resumed) { showCategoriesOnResume = false; showCategorySettings(); }
        }
    }

    @Override public void onAppLaunch(AppEntry requested) {
        if (scanInProgress || preparingPage || launchPending) return;
        AppEntry entry = findEntry(requested);
        if (entry == null) return;
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(entry.packageName);
            if (intent == null) {
                Intent query = new Intent(Intent.ACTION_MAIN).addCategory("tv.ouya.intent.category.GAME")
                        .setPackage(entry.packageName);
                List<ResolveInfo> matches = getPackageManager().queryIntentActivities(query, 0);
                if (matches != null && !matches.isEmpty() && matches.get(0).activityInfo != null) {
                    ActivityInfo info = matches.get(0).activityInfo;
                    intent = new Intent(Intent.ACTION_MAIN).setClassName(info.packageName, info.name)
                            .addCategory("tv.ouya.intent.category.GAME");
                }
            }
            if (intent == null) throw new IllegalStateException("No launch activity for " + entry.packageName);
            rememberTab();
            launchPending = true;
            startActivity(intent);
            handler.postDelayed(() -> launchPending = false, 1000);
        } catch (Exception error) {
            launchPending = false;
            Log.w("OUYAlaunch", "Cannot launch " + entry.packageName, error);
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onGenreChange(AppEntry requested) {
        if (scanInProgress || preparingPage) return;
        AppEntry entry = findEntry(requested);
        if (entry == null) return;
        int position = adapter.positionOf(entry.packageName);
        String oldGenre = entry.genre;
        int index = GENRES.indexOf(oldGenre);
        int next = categories.nextAssignable(index);
        if (next < 0) {
            Toast.makeText(this, R.string.no_assignable_category, Toast.LENGTH_SHORT).show();
            return;
        }
        if (GENRES.get(next).equals(oldGenre)) return;
        entry.genre = GENRES.get(next);
        dirtyGenre(oldGenre);
        dirtyGenre(entry.genre);
        if (entry.favorite) tabDirty[0] = true;
        saveApps();
        refreshEntries(entry.packageName, position);
    }

    @Override public void onToggleFavorite(AppEntry requested) {
        if (scanInProgress || preparingPage) return;
        AppEntry entry = findEntry(requested);
        if (entry == null) return;
        int position = adapter.positionOf(entry.packageName);
        entry.favorite = !entry.favorite;
        tabDirty[0] = true;
        saveApps();
        if (currentTab == 0) refreshEntries(entry.packageName, position);
    }

    private void removeSelectedApp() {
        if (scanInProgress || preparingPage) return;
        AppEntry entry = findEntry(adapter.getSelectedApp());
        if (entry == null) return;
        int position = adapter.positionOf(entry.packageName);
        allApps.remove(entry);
        dirtyGenre(entry.genre);
        tabDirty[0] = true;
        saveApps();
        refreshEntries(null, position);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int directionKey = event.getKeyCode();
        if (!preparingPage && event.getAction() == KeyEvent.ACTION_DOWN
                && (directionKey == KeyEvent.KEYCODE_DPAD_DOWN || directionKey == KeyEvent.KEYCODE_DPAD_UP
                || directionKey == KeyEvent.KEYCODE_DPAD_LEFT || directionKey == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            // User navigation supersedes any posted focus restoration from a previous page.
            ++focusGeneration;
        }
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_BUTTON_MODE || key == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) showCategorySettings();
            return true;
        }
        if (scanInProgress) return true;
        if (preparingPage && key != KeyEvent.KEYCODE_BUTTON_L1 && key != KeyEvent.KEYCODE_BUTTON_R1) return true;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        boolean first = down && event.getRepeatCount() == 0;
        if (key == KeyEvent.KEYCODE_BUTTON_L1 || key == KeyEvent.KEYCODE_BUTTON_R1) {
            if (first) selectTab(categories.neighbor(requestedTab >= 0 ? requestedTab : currentTab,
                    key == KeyEvent.KEYCODE_BUTTON_L1 ? -1 : 1));
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_L2 || key == KeyEvent.KEYCODE_BUTTON_R2) {
            if (down || up) {
                if (key == KeyEvent.KEYCODE_BUTTON_L2) l2Pressed = down;
                else r2Pressed = down;
                if (first && l2Pressed && r2Pressed) {
                    resetButtons();
                    startLoading(true);
                }
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_Y) {
            if (first) { yPressed = true; yDownAt = SystemClock.uptimeMillis(); }
            else if (up) {
                boolean remove = yPressed && !event.isCanceled() && SystemClock.uptimeMillis() - yDownAt >= 2000;
                yPressed = false;
                if (remove) removeSelectedApp();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void resetButtons() { l2Pressed = false; r2Pressed = false; yPressed = false; }

    private void updateNetworkIcon() {
        int icon = R.drawable.ic_offline;
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (manager != null) {
                NetworkInfo lan = manager.getNetworkInfo(ConnectivityManager.TYPE_ETHERNET);
                NetworkInfo wifi = manager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (lan != null && lan.isConnected()) icon = R.drawable.ic_lan;
                else if (wifi != null && wifi.isConnected()) {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                    WifiInfo info = wm == null ? null : wm.getConnectionInfo();
                    int level = info == null ? 0 : WifiManager.calculateSignalLevel(info.getRssi(), WIFI_ICONS.length);
                    icon = WIFI_ICONS[Math.max(0, Math.min(WIFI_ICONS.length - 1, level))];
                }
            }
        } catch (RuntimeException error) { Log.w("OUYAlaunch", "Network status unavailable", error); }
        if (icon != networkIcon) {
            networkIcon = icon;
            imgNetworkStatus.setImageResource(icon);
            imgNetworkStatus.setRotation(0f);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        launchPending = false;
        resetButtons();
        adapter.setImagesActive(true);
        if (!scanInProgress && !preparingPage) {
            if (loaded) restoreFocus(tabFocus[currentTab], Math.max(0, grid.findFirstVisibleItemPosition()));
        }
        refreshGenreNames(this);
        if (showCategoriesOnResume) {
            showCategoriesOnResume = false;
            handler.post(this::showCategorySettings);
        }
        handler.removeCallbacks(networkUpdate);
        networkUpdate.run();
    }

    @Override protected void onPause() {
        resumed = false;
        pausedAt = SystemClock.uptimeMillis();
        stoppedSincePause = false;
        resetButtons();
        handler.removeCallbacks(networkUpdate);
        if (loaded) rememberTab();
        super.onPause();
    }

    @Override protected void onStop() {
        stoppedSincePause = true;
        adapter.setImagesActive(false);
        super.onStop();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // UI_HIDDEN alone is not memory pressure; it arrives on every game launch.
        if (adapter != null && level != TRIM_MEMORY_UI_HIDDEN && level >= TRIM_MEMORY_RUNNING_LOW) {
            adapter.trimMemory(level >= TRIM_MEMORY_RUNNING_CRITICAL);
        }
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (adapter != null) adapter.trimMemory(true);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (loaded) rememberTab();
        state.putInt("tab", currentTab);
        for (int i = 0; i < GENRES.size(); i++) {
            state.putParcelable("scroll" + i, tabScroll[i]);
            state.putString("focus" + i, tabFocus[i]);
            state.putInt("first" + i, tabFirstVisible[i]);
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        cancelPageReveal();
        recycler.animate().cancel();
        pageGeneration++;
        if (categoryDialog != null) categoryDialog.dismiss();
        handler.removeCallbacksAndMessages(null);
        findViewById(android.R.id.content).animate().cancel();
        adapter.close();
        recycler.setAdapter(null);
        super.onDestroy();
    }

    private void ensureGridFitsFixedTiles(GridLayoutManager glm) {
        if (recycler == null || glm == null) return;

        final int tileW = getResources().getDimensionPixelSize(R.dimen.tile_width_px);
        final int spacing = getResources().getDimensionPixelSize(R.dimen.grid_spacing_px);
        final int screenW = getResources().getDisplayMetrics().widthPixels;

        int rvWidth = recycler.getWidth();
        if (rvWidth <= 0) rvWidth = screenW;

        int paddingLeft = recycler.getPaddingLeft();
        int paddingRight = recycler.getPaddingRight();
        int contentW = Math.max(0, rvWidth - paddingLeft - paddingRight);

        int span = glm.getSpanCount();
        if (span <= 0) span = 3;

        // Wenn die Spalte die feste Kachelbreite (plus etwas Luft für Spacing) nicht aufnehmen kann,
        // schalten wir auf pixelbasierte Zentrierung um und reduzieren ggf. die Spaltenanzahl.
        int colW = (span > 0) ? (contentW / span) : contentW;
        if (colW >= tileW) {
            return; // alles gut, OUYA/Standard-Look bleibt unverändert
        }

        // Horizontal-Margins entfernen, damit RecyclerView die volle Breite nutzen kann.
        ViewGroup.LayoutParams lp = recycler.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            mlp.leftMargin = 0;
            mlp.rightMargin = 0;
            // XML nutzt layout_marginStart/End -> auf API17+ auch Start/End explizit nullen
            MarginLayoutParamsCompat.setMarginStart(mlp, 0);
            MarginLayoutParamsCompat.setMarginEnd(mlp, 0);
            recycler.setLayoutParams(mlp);
        }

        // Spaltenanzahl anhand der echten Bildschirmbreite bestimmen.
        // (recycler.getWidth() kann hier noch die alten dp-Margins berücksichtigen -> führt sonst zu 2 Spalten + Linksversatz)
        int desiredSpan = 3;
        while (desiredSpan > 1) {
            int needed = desiredSpan * (tileW + spacing);
            if (needed <= screenW) break;
            desiredSpan--;
        }
        if (desiredSpan != glm.getSpanCount()) glm.setSpanCount(desiredSpan);

        int needed = desiredSpan * (tileW + spacing);
        int leftover = screenW - needed;
        int sidePadPx = Math.max(0, leftover / 2);

        // Top/Bottom Padding beibehalten (z.B. 6dp oben).
        int top = recycler.getPaddingTop();
        int bottom = recycler.getPaddingBottom();
        recycler.setPadding(sidePadPx, top, sidePadPx, bottom);

        recycler.requestLayout();
    }

}
