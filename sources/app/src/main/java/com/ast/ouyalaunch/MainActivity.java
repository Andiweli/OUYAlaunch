package com.ast.ouyalaunch;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
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
    private ControllerGridLayoutManager grid;
    private View overlayLoading;
    private SlidingTabLayout tabContainer;
    private View tabsBar;
    private View tabViewport;
    private android.view.ViewTreeObserver.OnGlobalLayoutListener headerLayoutListener;
    private android.view.ViewTreeObserver.OnPreDrawListener pageReveal;
    private ImageView imgNetworkStatus;
    private ImageView imgHeaderSettings;
    private ImageView imgHeaderApps;
    private View headerSettingsAction;
    private View headerAppsAction;
    private View headerNetworkAction;
    private ImageView background;
    private boolean uiResourcesReleased;
    private boolean pageNeedsReload = true;
    private int deferredTab = -1;
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
    private androidx.appcompat.app.AlertDialog actionDialog;
    private boolean preparingPage;
    private int requestedTab = -1;
    private int pageGeneration;
    private int lastTabDirection = 1;
    private boolean pageEntering;
    private final Runnable warmNextPage = this::prefetchNextTab;
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
    private boolean upperBarNavigation;
    private int headerActionIndex;
    private boolean l2Pressed, r2Pressed, triggerChord;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        recycler = findViewById(R.id.recycler);
        overlayLoading = findViewById(R.id.overlay_loading);
        tabContainer = findViewById(R.id.tab_container);
        tabsBar = findViewById(R.id.tabs_bar);
        tabViewport = findViewById(R.id.tab_scroll);
        background = findViewById(R.id.bg);
        headerSettingsAction = findViewById(R.id.header_settings_action);
        headerAppsAction = findViewById(R.id.header_apps_action);
        headerNetworkAction = findViewById(R.id.header_network_action);
        imgHeaderSettings = findViewById(R.id.imgHeaderSettings);
        imgHeaderApps = findViewById(R.id.imgHeaderApps);
        imgNetworkStatus = findViewById(R.id.imgNetworkStatus);
        int headerIconTint = Color.parseColor("#D8D9DC");
        imgHeaderSettings.setColorFilter(headerIconTint, PorterDuff.Mode.SRC_IN);
        imgHeaderApps.setColorFilter(headerIconTint, PorterDuff.Mode.SRC_IN);
        imgNetworkStatus.setColorFilter(headerIconTint, PorterDuff.Mode.SRC_IN);
        headerSettingsAction.setOnClickListener(view -> openAndroidSettings());
        headerAppsAction.setOnClickListener(view -> openAppsSettings());
        headerNetworkAction.setOnClickListener(view -> openInternetSettings());
        updateHeaderActionBackgrounds();
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
                if (!canPrefetch()) return;
                if (dy < 0) adapter.prefetch(grid.findFirstVisibleItemPosition() - 1, -1);
                else if (dy > 0) adapter.prefetch(grid.findLastVisibleItemPosition() + 1, 1);
            }
        });
        recycler.post(() -> ensureGridFitsFixedTiles(grid));
        // Also runs after row attachment, density-dependent grid fitting and tab edits.
        // Only changed margins request a layout; focus scaling never moves the tabs.
        headerLayoutListener = this::alignTabsWithGrid;
        recycler.getViewTreeObserver().addOnGlobalLayoutListener(headerLayoutListener);
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
            title.setGravity(Gravity.CENTER);
            title.setOnClickListener(view -> selectTab(index));
            // Keep each label's original natural width and padding, then share spare
            // space evenly. Long renamed tabs can still scroll instead of shrinking text.
            title.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            tabContainer.addView(title);
        }
        View.OnLongClickListener editCategories = view -> { showCategorySettings(); return true; };
        findViewById(R.id.header).setOnLongClickListener(editCategories);
        tabsBar.setOnLongClickListener(editCategories);
        updateTabBackgrounds();
    }

    private void updateTabBackgrounds() {
        if (upperBarNavigation) {
            for (int i = 0; i < tabContainer.getChildCount(); i++) {
                tabContainer.getChildAt(i).setSelected(false);
            }
            tabContainer.clearSelection();
            return;
        }
        int highlighted = requestedTab >= 0 ? requestedTab : currentTab;
        for (int i = 0; i < tabContainer.getChildCount(); i++) {
            View view = tabContainer.getChildAt(i);
            view.setSelected(i == highlighted);
        }
        tabContainer.select(highlighted);
        if (uiResourcesReleased) tabContainer.finishAnimation();
        else keepSelectedTabVisible();
    }

    private void updateHeaderActionBackgrounds() {
        if (headerSettingsAction == null || headerAppsAction == null || headerNetworkAction == null) return;
        int selected = getResources().getColor(R.color.gray_selected);
        headerSettingsAction.setBackgroundColor(upperBarNavigation && headerActionIndex == 0
                ? selected : Color.TRANSPARENT);
        headerAppsAction.setBackgroundColor(upperBarNavigation && headerActionIndex == 1
                ? selected : Color.TRANSPARENT);
        headerNetworkAction.setBackgroundColor(upperBarNavigation && headerActionIndex == 2
                ? selected : Color.TRANSPARENT);
    }

    /** L2/R2 switches the controller cursor between the category strip and the upper actions. */
    private void toggleNavigationBar() {
        upperBarNavigation = !upperBarNavigation;
        if (upperBarNavigation) {
            // The page/tab itself stays untouched while the synthetic cursor is upstairs.
            // Entering the upper bar still starts on the cogwheel as before.
            headerActionIndex = 0;
            updateHeaderActionBackgrounds();
            updateTabBackgrounds();
            return;
        }

        // Returning from the system-action bar must restore the tab that was open before
        // the toggle. Do not call selectTab(firstVisible): that used to jump to Favorites.
        updateHeaderActionBackgrounds();
        updateTabBackgrounds();
    }

    private void moveHeaderAction(int direction) {
        int next = Math.max(0, Math.min(2, headerActionIndex + direction));
        if (next == headerActionIndex) return;
        headerActionIndex = next;
        updateHeaderActionBackgrounds();
    }

    private void activateHeaderAction() {
        if (headerActionIndex == 0) openAndroidSettings();
        else if (headerActionIndex == 1) openAppsSettings();
        else openInternetSettings();
    }

    private void openAndroidSettings() {
        openSettingsIntent(Settings.ACTION_SETTINGS, null);
    }

    private void openAppsSettings() {
        // Open Android's installed-app management screen. This action is available
        // on Android 4.1.2 (API 16); keep the broader app settings page as fallback.
        openSettingsIntent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
                Settings.ACTION_APPLICATION_SETTINGS);
    }

    private void openInternetSettings() {
        // Android 4.1.2/OUYA exposes Wi-Fi settings through this standard action.
        // Some vendor builds omit it, so fall back to the broader wireless panel.
        openSettingsIntent(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS);
    }

    private void openSettingsIntent(String action, String fallbackAction) {
        if (scanInProgress || preparingPage || launchPending) return;
        rememberTab();
        try {
            launchPending = true;
            startActivity(new Intent(action));
            handler.postDelayed(() -> launchPending = false, 1000);
            return;
        } catch (RuntimeException firstError) {
            launchPending = false;
            if (fallbackAction != null) {
                try {
                    launchPending = true;
                    startActivity(new Intent(fallbackAction));
                    handler.postDelayed(() -> launchPending = false, 1000);
                    return;
                } catch (RuntimeException fallbackError) {
                    Log.w("OUYAlaunch", "Could not open settings action " + fallbackAction, fallbackError);
                    launchPending = false;
                }
            } else {
                Log.w("OUYAlaunch", "Could not open settings action " + action, firstError);
            }
        }
        Toast.makeText(this, R.string.settings_launch_failed, Toast.LENGTH_SHORT).show();
    }

    private void keepSelectedTabVisible() {
        View selected = tabContainer.getChildAt(requestedTab >= 0 ? requestedTab : currentTab);
        if (selected != null) selected.post(() -> {
            if (!destroyed && !uiResourcesReleased && selected.getVisibility() == View.VISIBLE) {
                selected.requestRectangleOnScreen(new android.graphics.Rect(0, 0,
                        selected.getWidth(), selected.getHeight()), true);
            }
        });
    }

    private void rememberTab() {
        if (preparingPage || uiResourcesReleased || pageNeedsReload) return;
        tabScroll[currentTab] = grid.onSaveInstanceState();
        tabFirstVisible[currentTab] = Math.max(0, grid.findFirstVisibleItemPosition());
        // During an animated row move the Android focus intentionally remains on the
        // old tile until the destination row has settled. Preserve the logical target
        // rather than accidentally saving that old tile when a tab/game is opened mid-scroll.
        int pending = grid.getPendingControllerFocusPosition();
        List<AppEntry> entries = currentEntries();
        if (pending >= 0 && pending < entries.size()) {
            tabFocus[currentTab] = entries.get(pending).packageName;
            return;
        }
        String selected = adapter.getSelectedPackage();
        if (adapter.positionOf(selected) >= 0) tabFocus[currentTab] = selected;
    }

    private void selectTab(int index) {
        if (scanInProgress || index < 0 || index >= GENRES.size() || !categories.enabled(index)) return;
        // Re-selecting the highlighted destination must not restart its load or lose its direction.
        if (preparingPage && index == requestedTab) return;
        // The fast reverse shortcut is safe only while the old page still owns its
        // images. After a memory release, reversing must prepare that page again.
        if (index == currentTab && !pageNeedsReload) {
            if (preparingPage) {
                lastTabDirection = index > requestedTab ? 1 : -1;
                cancelPrefetch();
                pageGeneration++;
                preparingPage = false;
                requestedTab = -1;
                adapter.cancelPreparation();
                cancelPageReveal();
                resetPagePresentation(1f);
                updateTabBackgrounds();
                restoreFocus(tabFocus[currentTab], tabFirstVisible[currentTab]);
            }
            return;
        }
        rememberTab();
        presentPage(index, false);
    }

    private void presentPage(int index, boolean initial) {
        if (destroyed) return;
        if (uiResourcesReleased) {
            // A scan/settings callback may arrive after onStop. Keep only its
            // requested tab; decoding and visual callbacks wait for onResume.
            deferredTab = index;
            return;
        }
        deferredTab = -1;
        // Capture the latest navigation direction BEFORE requestedTab/currentTab change.
        // A quick right/right/left sequence must enter from the left, even if the
        // final destination still lies to the right of the last fully loaded page.
        final float startTranslationX = TabPageMotion.startTranslationX(currentTab,
                requestedTab, index, initial,
                getResources().getDimensionPixelSize(R.dimen.tile_width_px));
        if (!initial && startTranslationX != 0f) lastTabDirection = startTranslationX > 0f ? 1 : -1;
        cancelPrefetch();
        // Do not let a superseded preparation occupy the decoder for another frame.
        adapter.cancelPreparation();
        ++focusGeneration;
        grid.cancelPendingControllerFocus();
        recycler.stopScroll();
        final int ticket = ++pageGeneration;
        preparingPage = true;
        requestedTab = index;
        cancelPageReveal();
        resetPagePresentation(0f);
        updateTabBackgrounds();
        // Let the hidden grid and moving header reach the screen before preparing the page.
        recycler.postOnAnimation(() -> preparePage(index, initial, ticket, startTranslationX));
    }

    /** Reset only the page's visual transform; never touch tile layout or row scroll offsets. */
    private void resetPagePresentation(float alpha) {
        recycler.animate().cancel();
        pageEntering = false;
        recycler.setTranslationX(0f);
        recycler.setAlpha(alpha);
    }

    private void preparePage(int index, boolean initial, int ticket, float startTranslationX) {
        if (destroyed || uiResourcesReleased || ticket != pageGeneration) return;
        final List<AppEntry> entries = new ArrayList<>(entriesForTab(index));
        final PageLoadWindow window = pageLoadWindow(index, entries);
        adapter.preparePage(entries, window.first, window.count, () -> {
            if (destroyed || uiResourcesReleased || ticket != pageGeneration) return;
            if (!initial && loaded) rememberTab();
            currentTab = index;
            recycler.clearFocus();
            adapter.replacePage(entries);
            updateTabBackgrounds();
            if (tabScroll[index] != null) grid.onRestoreInstanceState(tabScroll[index]);
            else grid.scrollToPositionWithOffset(window.first, 0);
            restoreFocus(tabFocus[index], window.first);
            revealPageAfterLayout(ticket, startTranslationX);
        });
    }

    private PageLoadWindow pageLoadWindow(int index, List<AppEntry> entries) {
        int height = recycler.getHeight();
        if (height <= 0) height = getResources().getDisplayMetrics().heightPixels
                - getResources().getDimensionPixelSize(R.dimen.header_height_px)
                - getResources().getDimensionPixelSize(R.dimen.header_divider_height_px)
                - getResources().getDimensionPixelSize(R.dimen.tabs_bar_height_px);
        int row = getResources().getDimensionPixelSize(R.dimen.tile_height_px)
                + getResources().getDimensionPixelSize(R.dimen.grid_spacing_px);
        int focus = -1;
        String savedFocus = tabFocus[index];
        if (savedFocus != null) for (int i = 0; i < entries.size(); i++) {
            if (savedFocus.equals(entries.get(i).packageName)) { focus = i; break; }
        }
        return PageLoadWindow.forViewport(entries.size(), grid.getSpanCount(),
                tabFirstVisible[index], focus, height, recycler.getPaddingTop(), row);
    }

    private boolean canPrefetch() {
        return resumed && loaded && !destroyed && !uiResourcesReleased && !scanInProgress
                && !preparingPage && !pageEntering;
    }

    private void cancelPrefetch() {
        handler.removeCallbacks(warmNextPage);
        adapter.cancelPrefetch();
    }

    private void prefetchAfterEntrance() {
        if (!canPrefetch()) return;
        adapter.prefetch(grid.findLastVisibleItemPosition() + 1, 1);
        // Queue the next tab after the current page is visible. It must never be
        // part of the reveal barrier, and its I/O stays behind current-page work.
        handler.removeCallbacks(warmNextPage);
        handler.post(warmNextPage);
    }

    private void prefetchNextTab() {
        if (!canPrefetch()) return;
        int next = categories.neighbor(currentTab, lastTabDirection);
        if (next == currentTab) return; // No wrapping/guessing at an end tab.
        List<AppEntry> entries = entriesForTab(next);
        PageLoadWindow window = pageLoadWindow(next, entries);
        adapter.prefetchPage(entries, window.first, window.count);
    }

    private void cancelPageReveal() {
        if (pageReveal != null) {
            recycler.getViewTreeObserver().removeOnPreDrawListener(pageReveal);
            pageReveal = null;
        }
    }

    private void revealPageAfterLayout(int ticket, float startTranslationX) {
        cancelPageReveal();
        pageReveal = () -> {
            if (destroyed || uiResourcesReleased || ticket != pageGeneration) { cancelPageReveal(); return true; }
            if (recycler.hasPendingAdapterUpdates() || recycler.isLayoutRequested()) return true;
            cancelPageReveal();
            preparingPage = false;
            pageNeedsReload = false;
            requestedTab = -1;
            overlayLoading.setVisibility(View.GONE);
            if (!resumed) {
                // A page finishing in the background must not resume partly translated.
                resetPagePresentation(1f);
                return true;
            }
            // Move the loaded page as one unit, independently of each tile's focus zoom.
            // Layout coordinates and the fixed vertical scroll raster stay untouched.
            pageEntering = true;
            recycler.setTranslationX(startTranslationX);
            recycler.animate().alpha(1f).translationX(0f)
                    .setStartDelay(0L).setDuration(TabPageMotion.DURATION_MS)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction(() -> {
                        if (destroyed || uiResourcesReleased || ticket != pageGeneration) return;
                        pageEntering = false;
                        prefetchAfterEntrance();
                    }).start();
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
            if (destroyed || uiResourcesReleased || generation != focusGeneration) return;
            if (adapter.getItemCount() == 0) {
                tabContainer.getChildAt(currentTab).requestFocus();
                return;
            }
            RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(position);
            if (holder != null) holder.itemView.requestFocus();
            else {
                grid.scrollToPosition(position);
                recycler.post(() -> {
                    if (destroyed || uiResourcesReleased || generation != focusGeneration) return;
                    RecyclerView.ViewHolder next = recycler.findViewHolderForAdapterPosition(position);
                    if (next != null) next.itemView.requestFocus();
                });
            }
            prefetchAfterEntrance();
        });
    }

    private void refreshEntries(String preferred, int fallback) {
        adapter.setData(currentEntries());
        restoreFocus(preferred, fallback);
    }

    private AppEntry findEntry(AppEntry requested) {
        return requested == null ? null : findEntryByPackage(requested.packageName);
    }

    private AppEntry findEntryByPackage(String packageName) {
        if (packageName == null) return null;
        for (AppEntry entry : allApps) {
            if (packageName.equals(entry.packageName)) return entry;
        }
        return null;
    }

    private void startLoading(boolean manualScan) {
        if (scanInProgress) return;
        cancelPrefetch();
        pageGeneration++;
        preparingPage = false;
        requestedTab = -1;
        cancelPageReveal();
        resetPagePresentation(1f);
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
                    if (manualScan) {
                        target.adapter.invalidateIcons();
                        target.pageNeedsReload = true;
                    }
                    // Finish metadata work without resurrecting a hidden UI.
                    int page = target.deferredTab >= 0 ? target.deferredTab : target.currentTab;
                    if (!preferences.enabled(page)) page = preferences.firstVisible();
                    target.adapter.setImagesActive(target.resumed && !target.uiResourcesReleased, false);
                    target.presentPage(page, true);
                });
            } catch (Exception error) {
                Log.e("OUYAlaunch", "Could not load/scan apps", error);
                MAIN.post(() -> {
                    MainActivity target = owner.get();
                    if (target == null || target.destroyed) return;
                    target.scanInProgress = false;
                    target.overlayLoading.setVisibility(View.GONE);
                    target.adapter.setImagesActive(target.resumed && !target.uiResourcesReleased);
                    if (target.loaded && !target.uiResourcesReleased) target.presentPage(target.currentTab, true);
                    else if (!target.uiResourcesReleased) target.resetPagePresentation(1f);
                    if (target.resumed) Toast.makeText(target, R.string.load_failed, Toast.LENGTH_LONG).show();
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
        cancelPrefetch();
        categories = settings;
        updateCategoryTabs();
        int target = deferredTab >= 0 ? deferredTab : (requestedTab >= 0 ? requestedTab : currentTab);
        if (!categories.enabled(target)) {
            target = categories.enabled(currentTab) ? currentTab : categories.firstVisible();
            if (loaded && !scanInProgress) presentPage(target, false);
            else currentTab = target;
        }
        prefetchAfterEntrance();
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
        if (scanInProgress || preparingPage || actionDialog != null) return;
        AppEntry entry = findEntry(requested);
        if (entry == null) return;
        final String packageName = entry.packageName;
        androidx.appcompat.app.AlertDialog dialog = CategoryDialog.showMoveGenre(this, categories, entry.genre,
                genreIndex -> moveGameToGenre(packageName, genreIndex));
        if (dialog == null) return;
        actionDialog = dialog;
        dialog.setOnDismissListener(dismissed -> {
            if (actionDialog == dialog) actionDialog = null;
            resetButtons();
        });
    }

    private void moveGameToGenre(String packageName, int genreIndex) {
        if (scanInProgress || preparingPage || genreIndex <= 0 || genreIndex >= GENRES.size()
                || !categories.enabled(genreIndex)) return;
        AppEntry entry = findEntryByPackage(packageName);
        if (entry == null) return;
        String destination = GENRES.get(genreIndex);
        if (destination.equals(entry.genre)) return;
        int position = adapter.positionOf(entry.packageName);
        String oldGenre = entry.genre;
        entry.genre = destination;
        dirtyGenre(oldGenre);
        dirtyGenre(destination);
        if (entry.favorite) tabDirty[0] = true;
        saveApps();
        refreshEntries(entry.packageName, position);
    }

    @Override public void onToggleFavorite(AppEntry requested) {
        if (scanInProgress || preparingPage || actionDialog != null) return;
        AppEntry entry = findEntry(requested);
        if (entry == null) return;
        final String packageName = entry.packageName;
        final int message = entry.favorite
                ? R.string.confirm_remove_favorite
                : R.string.confirm_set_favorite;
        showGameConfirmation(message, () -> toggleFavoriteConfirmed(packageName));
    }

    private void toggleFavoriteConfirmed(String packageName) {
        if (scanInProgress || preparingPage) return;
        AppEntry entry = findEntryByPackage(packageName);
        if (entry == null) return;
        int position = adapter.positionOf(entry.packageName);
        entry.favorite = !entry.favorite;
        tabDirty[0] = true;
        saveApps();
        if (currentTab == 0) refreshEntries(entry.packageName, position);
    }

    private void confirmRemoveSelectedApp() {
        if (scanInProgress || preparingPage || actionDialog != null) return;
        AppEntry entry = findEntry(adapter.getSelectedApp());
        if (entry == null) return;
        final String packageName = entry.packageName;
        showGameConfirmation(R.string.confirm_delete_game, () -> removeAppConfirmed(packageName));
    }

    private void removeAppConfirmed(String packageName) {
        if (scanInProgress || preparingPage) return;
        AppEntry entry = findEntryByPackage(packageName);
        if (entry == null) return;
        int position = adapter.positionOf(entry.packageName);
        allApps.remove(entry);
        dirtyGenre(entry.genre);
        tabDirty[0] = true;
        saveApps();
        refreshEntries(null, position);
    }

    /** Small controller-first requester: OUYA O activates the focused button; OUYA A always cancels. */
    private void showGameConfirmation(int messageRes, Runnable confirmed) {
        if (destroyed || isFinishing() || actionDialog != null) return;
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(messageRes)
                .setPositiveButton(R.string.ok, (whichDialog, which) -> confirmed.run())
                .setNegativeButton(R.string.cancel, null)
                .create();
        actionDialog = dialog;
        dialog.setOnDismissListener(dismissed -> {
            if (actionDialog == dialog) actionDialog = null;
        });
        dialog.setOnKeyListener((whichDialog, keyCode, event) -> {
            // OUYA labels: O -> BUTTON_A, A -> BUTTON_B. Consume DOWN and UP so the
            // focused game tile never receives the confirmation key afterwards.
            // O activates whichever requester button currently has focus; A always cancels.
            if (keyCode != KeyEvent.KEYCODE_BUTTON_A && keyCode != KeyEvent.KEYCODE_BUTTON_B) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                android.widget.Button ok = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
                android.widget.Button cancel = dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
                if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                    if (cancel != null) cancel.performClick();
                } else {
                    android.view.View focused = dialog.getCurrentFocus();
                    if (focused == cancel && cancel != null) cancel.performClick();
                    else if (focused == ok && ok != null) ok.performClick();
                }
            }
            return true;
        });
        dialog.setOnShowListener(shown -> {
            android.widget.Button ok = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            if (ok != null) ok.requestFocus();
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int preferred = Math.round(460f * getResources().getDisplayMetrics().density);
                int width = Math.min(preferred, Math.round(screenWidth * 0.70f));
                window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setGravity(Gravity.CENTER);
            }
        });
        dialog.show();
    }

    /**
     * Move exactly one controller row while preserving the tile column. RecyclerView's
     * geometric FocusFinder is deliberately bypassed here: during a smooth scroll it can
     * see transient child bounds and prefer the visually-nearest centre-column tile.
     */
    private void moveTileFocusVertically(int rowDelta) {
        if (!loaded || preparingPage || adapter == null || adapter.getItemCount() == 0) return;
        int position = RecyclerView.NO_POSITION;
        View focused = recycler.getFocusedChild();
        if (focused != null) position = recycler.getChildAdapterPosition(focused);
        if (position == RecyclerView.NO_POSITION) {
            position = adapter.positionOf(adapter.getSelectedPackage());
        }
        if (position == RecyclerView.NO_POSITION) {
            position = Math.max(0, grid.findFirstVisibleItemPosition());
        }
        grid.moveControllerFocus(recycler, position, rowDelta);
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
        boolean triggerKey = key == KeyEvent.KEYCODE_BUTTON_L2 || key == KeyEvent.KEYCODE_BUTTON_R2;
        if (preparingPage && key != KeyEvent.KEYCODE_BUTTON_L1 && key != KeyEvent.KEYCODE_BUTTON_R1
                && !triggerKey) return true;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        boolean first = down && event.getRepeatCount() == 0;

        if (triggerKey) {
            // A single L2 or R2 toggles bars on release. Holding both keeps the old
            // manual-rescan shortcut without causing two unwanted bar toggles.
            if (first) {
                if (key == KeyEvent.KEYCODE_BUTTON_L2) l2Pressed = true;
                else r2Pressed = true;
                if (l2Pressed && r2Pressed && !triggerChord) {
                    triggerChord = true;
                    resetButtons();
                    startLoading(true);
                }
            } else if (up) {
                boolean wasPressed = key == KeyEvent.KEYCODE_BUTTON_L2 ? l2Pressed : r2Pressed;
                boolean wasChord = triggerChord;
                if (key == KeyEvent.KEYCODE_BUTTON_L2) l2Pressed = false;
                else r2Pressed = false;
                if (wasPressed && !wasChord && !event.isCanceled()) toggleNavigationBar();
                if (!l2Pressed && !r2Pressed) triggerChord = false;
            }
            return true;
        }

        if (key == KeyEvent.KEYCODE_BUTTON_L1 || key == KeyEvent.KEYCODE_BUTTON_R1) {
            if (first) {
                int direction = key == KeyEvent.KEYCODE_BUTTON_L1 ? -1 : 1;
                if (upperBarNavigation) moveHeaderAction(direction);
                else selectTab(categories.neighbor(requestedTab >= 0 ? requestedTab : currentTab, direction));
            }
            return true;
        }

        if (upperBarNavigation) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (first) moveHeaderAction(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_DPAD_CENTER
                    || key == KeyEvent.KEYCODE_ENTER) {
                if (first) activateHeaderAction();
                return true;
            }
            // The game tile remains the focused Android View while the synthetic cursor
            // is in the upper bar. Consume OUYA U/Y/A here so its tile-only actions
            // (genre/delete/favorite) cannot leak through. O still activates the header.
            if (key == KeyEvent.KEYCODE_BUTTON_X || key == KeyEvent.KEYCODE_BUTTON_Y
                    || key == KeyEvent.KEYCODE_BUTTON_B) return true;
        }

        // Own vertical tile navigation instead of delegating it to Android FocusFinder.
        // ACTION_DOWN repeats are intentional: each repeat advances one logical row,
        // while ControllerGridLayoutManager retargets the same short smooth scroll.
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down) moveTileFocusVertically(key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            return true;
        }

        if (key == KeyEvent.KEYCODE_BUTTON_Y) {
            if (first) confirmRemoveSelectedApp();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void resetButtons() {
        l2Pressed = false;
        r2Pressed = false;
        triggerChord = false;
    }

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

    @Override protected void onStart() {
        super.onStart();
        restoreBackground();
    }

    /** Own the large backdrop directly, not through a shared drawable resource cache. */
    private void restoreBackground() {
        if (background == null || background.getDrawable() != null) return;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false; // bg.png is nodpi; preserve its original pixels/centerCrop.
            background.setImageBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.bg, options));
        } catch (RuntimeException | OutOfMemoryError error) {
            // Keep the existing black root rather than crashing under game-exit memory pressure.
            Log.w("OUYAlaunch", "Background unavailable", error);
        }
    }

    /** Called only once the launcher is fully hidden, not merely covered by a dialog. */
    private void releaseUiResources() {
        if (adapter == null) return;
        if (uiResourcesReleased) {
            // onStart may restore only the backdrop before another stop/trim arrives.
            // Keep repeated notifications safe without overwriting the saved viewport.
            if (background != null) background.setImageDrawable(null);
            return;
        }
        cancelPrefetch();
        recycler.stopScroll();
        if (loaded) rememberTab();
        grid.cancelPendingControllerFocus();
        deferredTab = requestedTab >= 0 ? requestedTab : currentTab;
        uiResourcesReleased = true;
        pageNeedsReload = true;
        ++pageGeneration;
        ++focusGeneration;
        preparingPage = false;
        requestedTab = -1;
        cancelPageReveal();
        resetPagePresentation(0f);
        tabContainer.finishAnimation();
        View root = findViewById(android.R.id.content);
        root.animate().cancel();
        root.setAlpha(1f);
        adapter.releaseUiMemory();
        background.setImageDrawable(null);
        // Views/data/row state stay intact. No bitmap.recycle() on displayed/shared
        // pixels, no forced GC and no process kill; Android reclaims unreachable memory.
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        launchPending = false;
        resetButtons();
        boolean restorePage = uiResourcesReleased;
        uiResourcesReleased = false;
        restoreBackground();
        adapter.setImagesActive(!scanInProgress, !restorePage);
        if (!scanInProgress) {
            if (restorePage && loaded) {
                int page = deferredTab >= 0 ? deferredTab : currentTab;
                if (!categories.enabled(page)) page = categories.firstVisible();
                // Reuse the visible-only load barrier and saved row/focus, with the
                // existing fade but no directional swipe for a return from a game.
                presentPage(page, true);
            } else if (!preparingPage && loaded) {
                restoreFocus(tabFocus[currentTab], Math.max(0, grid.findFirstVisibleItemPosition()));
            } else if (!loaded) resetPagePresentation(1f);
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
        cancelPrefetch();
        pausedAt = SystemClock.uptimeMillis();
        stoppedSincePause = false;
        resetButtons();
        handler.removeCallbacks(networkUpdate);
        if (loaded) rememberTab();
        // Finish a visible entrance before opening a game/dialog. A pending page stays
        // hidden until its own generation-checked reveal has completed preparation.
        if (!preparingPage) resetPagePresentation(1f);
        super.onPause();
    }

    @Override protected void onStop() {
        stoppedSincePause = true;
        releaseUiResources();
        super.onStop();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (adapter == null) return;
        if (!resumed && level >= TRIM_MEMORY_UI_HIDDEN) {
            // Some Android versions notify before onStop; release is idempotent.
            releaseUiResources();
        } else if (level != TRIM_MEMORY_UI_HIDDEN && level >= TRIM_MEMORY_RUNNING_LOW) {
            cancelPrefetch();
            adapter.trimMemory(level >= TRIM_MEMORY_RUNNING_CRITICAL);
        }
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (adapter != null) {
            if (uiResourcesReleased || (stoppedSincePause && !resumed)) releaseUiResources();
            else { cancelPrefetch(); adapter.trimMemory(true); }
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (loaded) rememberTab();
        state.putInt("tab", deferredTab >= 0 ? deferredTab : currentTab);
        for (int i = 0; i < GENRES.size(); i++) {
            state.putParcelable("scroll" + i, tabScroll[i]);
            state.putString("focus" + i, tabFocus[i]);
            state.putInt("first" + i, tabFirstVisible[i]);
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (headerLayoutListener != null) {
            recycler.getViewTreeObserver().removeOnGlobalLayoutListener(headerLayoutListener);
            headerLayoutListener = null;
        }
        cancelPageReveal();
        resetPagePresentation(1f);
        pageGeneration++;
        if (categoryDialog != null) categoryDialog.dismiss();
        if (actionDialog != null) actionDialog.dismiss();
        handler.removeCallbacksAndMessages(null);
        findViewById(android.R.id.content).animate().cancel();
        adapter.close();
        background.setImageDrawable(null);
        tabContainer.finishAnimation();
        recycler.setAdapter(null);
        super.onDestroy();
    }

    /** Align to actual tile edges, not the RecyclerView's outer margins or focus zoom. */
    private void alignTabsWithGrid() {
        if (destroyed || recycler == null || grid == null || tabsBar == null
                || tabViewport == null || recycler.getWidth() <= 0 || tabsBar.getWidth() <= 0) return;

        int span = Math.max(1, grid.getSpanCount());
        int contentWidth = Math.max(0, recycler.getWidth()
                - recycler.getPaddingLeft() - recycler.getPaddingRight());
        if (contentWidth == 0) return;
        int tileWidth = getResources().getDimensionPixelSize(R.dimen.tile_width_px);
        int halfSpacing = getResources().getDimensionPixelSize(R.dimen.grid_spacing_px) / 2;
        // The grid and tabsBar are siblings in main_content; these are bar-local coordinates.
        int gridLeft = recycler.getLeft() - tabsBar.getLeft();
        int left = gridLeft + recycler.getPaddingLeft() + halfSpacing;
        int right = left + HeaderGeometry.rowWidth(contentWidth, span, tileWidth);

        // Use laid-out edges when available. A short/empty category still retains the
        // complete row width, rather than collapsing to the number of installed games.
        for (int i = 0; i < recycler.getChildCount(); i++) {
            View child = recycler.getChildAt(i);
            ViewGroup.LayoutParams raw = child.getLayoutParams();
            if (!(raw instanceof GridLayoutManager.LayoutParams)
                    || recycler.getChildAdapterPosition(child) == RecyclerView.NO_POSITION) continue;
            GridLayoutManager.LayoutParams params = (GridLayoutManager.LayoutParams) raw;
            if (params.getSpanIndex() == 0) left = gridLeft + child.getLeft();
            if (params.getSpanIndex() == span - 1) right = gridLeft + child.getRight();
        }

        left = Math.max(0, Math.min(left, tabsBar.getWidth() - 1));
        right = Math.max(left + 1, Math.min(right, tabsBar.getWidth()));
        ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) tabViewport.getLayoutParams();
        int rightMargin = tabsBar.getWidth() - right;
        if (params.leftMargin == left && params.rightMargin == rightMargin) return;
        params.leftMargin = left;
        params.rightMargin = rightMargin;
        tabViewport.setLayoutParams(params);
    }

    private void ensureGridFitsFixedTiles(GridLayoutManager glm) {
        if (recycler == null || glm == null) return;

        final int tileW = getResources().getDimensionPixelSize(R.dimen.tile_width_px);
        final int spacing = getResources().getDimensionPixelSize(R.dimen.grid_spacing_px);
        View parent = recycler.getParent() instanceof View ? (View) recycler.getParent() : null;
        final int screenW = parent != null && parent.getWidth() > 0 ? parent.getWidth()
                : getResources().getDisplayMetrics().widthPixels;

        int rvWidth = recycler.getWidth();
        if (rvWidth <= 0) rvWidth = screenW;

        int paddingLeft = recycler.getPaddingLeft();
        int paddingRight = recycler.getPaddingRight();
        int contentW = Math.max(0, rvWidth - paddingLeft - paddingRight);

        int span = glm.getSpanCount();
        if (span <= 0) span = 3;

        // A fixed-width tile must occupy exactly tileW + spacing pixels per span.
        // Merely checking that it fits leaves unused span width on some densities/API
        // levels, so horizontal gaps differ from the vertical ItemDecoration spacing.
        // Keep the standard 1080p layout when it already has the correct row width;
        // otherwise use the existing centered fallback without changing tile sizes.
        if (contentW == span * (tileW + spacing)) {
            return;
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

        // Use the actual content-parent width (excluding any system UI), not stale grid margins.
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
        int rightPadPx = Math.max(0, leftover - sidePadPx);

        // Preserve the pixel-based top/bottom padding (6 px above the grid).
        int top = recycler.getPaddingTop();
        int bottom = recycler.getPaddingBottom();
        recycler.setPadding(sidePadPx, top, rightPadPx, bottom);

        recycler.requestLayout();
    }

}
