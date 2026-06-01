package com.ast.ouyalaunch;

import android.content.Context;
import android.util.Log;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.AsyncTask;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.MarginLayoutParamsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.ViewGroup;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import android.os.Handler;



public class MainActivity extends AppCompatActivity implements AppAdapter.OnAppClickListener {

    public static final List<String> GENRES = Arrays.asList("Favorites", "Casual", "Action", "Racing", "Simulation", "RPG", "Apps");

    private static final String STATE_PREFS = "ouyalaunch_state";
    private static final String KEY_PACKAGE_RESCAN_PENDING = "package_rescan_pending";
    private static final String KEY_LAST_SCAN_FINISHED_AT = "last_scan_finished_at";

    private RecyclerView recycler;
    private View overlayLoading;
    private LinearLayout tabContainer;

    private ImageView imgNetworkStatus;
    private final int[] WIFI_ICONS = {
            R.drawable.ic_wifi_0,
            R.drawable.ic_wifi_1,
            R.drawable.ic_wifi_2,
            R.drawable.ic_wifi_3,
            R.drawable.ic_wifi_4
    };

    private static final long NETWORK_UPDATE_INTERVAL_MS = 15_000L;
    private final Handler networkHandler = new Handler();
    private final Runnable networkUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateNetworkIcon();
            // nächsten Lauf in 15 Sekunden planen
            networkHandler.postDelayed(this, NETWORK_UPDATE_INTERVAL_MS);
        }
    };

    private AppAdapter adapter;
    private int currentTab = 0; // default "Favorites"
    private DataStore dataStore;
    private List<AppEntry> allApps = new ArrayList<>();
    private List<List<AppEntry>> tabLists;
    private boolean[] tabDirty;
    private boolean scanInProgress = false;
    private boolean pendingForegroundRescan = false;

    private static final long PACKAGE_RESCAN_FOREGROUND_DELAY_MS = 1500L;
    private final Handler rescanHandler = new Handler();
    private final Runnable foregroundPackageRescanRunnable = new Runnable() {
        @Override
        public void run() {
            runPendingPackageRescanIfVisible();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- Eindeutige App-UUID basierend auf Paketnamen ---
        String packageName = getPackageName();
        String appUUID = UUID.nameUUIDFromBytes(packageName.getBytes()).toString();
        Log.d("OUYAlaunch", "App UUID = " + appUUID);


        recycler = findViewById(R.id.recycler);
        overlayLoading = findViewById(R.id.overlay_loading);
        tabContainer = findViewById(R.id.tab_container);
        imgNetworkStatus = findViewById(R.id.imgNetworkStatus);

        applyNetworkIconTint();

        dataStore = new DataStore(this);

        updateNetworkIcon();

        GridLayoutManager glm = new GridLayoutManager(this, 3);
        recycler.setLayoutManager(glm);
        recycler.setHasFixedSize(true);
        recycler.setItemAnimator(null);
        recycler.setItemViewCacheSize(30);
        recycler.addItemDecoration(new SpacingDecoration(getResources().getDimensionPixelSize(R.dimen.grid_spacing_px)));
        adapter = new AppAdapter(this, new ArrayList<AppEntry>(), this);
        recycler.setAdapter(adapter);

        // Fix für Geräte mit gleicher Auflösung aber anderer DPI:
        // In activity_main.xml werden dp-Margins/Paddings verwendet (OUYA-Layout passt damit).
        // Auf manchen 1080p-Geräten werden diese dp-Werte zu groß -> Spaltenbreite < feste Tile-Breite -> Überlappung.
        // Wir passen nur dann dynamisch an, wenn die aktuelle Spaltenbreite die Tile-Breite nicht aufnehmen kann.
        recycler.post(new Runnable() {
            @Override
            public void run() {
                ensureGridFitsFixedTiles(glm);
            }
        });

        setupTabs();

        // First run: build the cache once. After that OUYAlaunch never rescans
        // automatically on package changes; only the manual L2+R2 rescan updates the cache.
        clearPackageRescanPending();
        if (!dataStore.isCacheBuilt()) {
            startInitialScanIfIdle();
        } else {
            allApps = dataStore.load();
            markAllTabsDirty();
            adapter.setData(filterByCurrentTab(allApps));
            focusFirstTileIfNeeded();
        }

        // 🟠 Sanfter Fade-In-Effekt beim Appstart
        View root = findViewById(android.R.id.content);
        root.setAlpha(0f); // zunächst unsichtbar
        root.animate()
                .alpha(1f)                     // auf volle Sichtbarkeit
                .setDuration(1500)             // Dauer 1,5 Sekunden
                .setStartDelay(100)            // kleine Verzögerung für flüssigen Start
                .start();

    }

    /**
     * Stellt sicher, dass das Grid mit festen Tile-Pixelmaßen (item_app.xml) nicht überlappt,
     * wenn dp-basierte Side-Margins/Paddings auf High-DPI-Geräten zu groß werden.
     *
     * OUYA soll unverändert bleiben: Wir greifen nur ein, wenn die berechnete Spaltenbreite
     * kleiner als die feste Tile-Breite ist.
     */
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

    private SharedPreferences launcherState() {
        return getSharedPreferences(STATE_PREFS, MODE_PRIVATE);
    }

    private boolean hasPackageRescanPending() {
        return launcherState().getBoolean(KEY_PACKAGE_RESCAN_PENDING, false);
    }

    private void clearPackageRescanPending() {
        launcherState().edit().putBoolean(KEY_PACKAGE_RESCAN_PENDING, false).apply();
    }

    private void markScanFinished() {
        launcherState().edit()
                .putLong(KEY_LAST_SCAN_FINISHED_AT, System.currentTimeMillis())
                .putBoolean(KEY_PACKAGE_RESCAN_PENDING, false)
                .apply();
    }

    private void focusFirstTileIfNeeded() {
        if (recycler == null) return;
        recycler.post(new Runnable() {
            @Override
            public void run() {
                if (recycler != null && recycler.getFocusedChild() == null && recycler.getChildCount() > 0) {
                    recycler.getChildAt(0).requestFocus();
                }
            }
        });
    }

    private boolean beginScanIfIdle(String reason) {
        if (scanInProgress) {
            Log.i("OUYAlaunch", "scan skipped, already running: " + reason);
            return false;
        }
        scanInProgress = true;
        Log.i("OUYAlaunch", "scan started: " + reason);
        return true;
    }

    private void endScan(String reason) {
        scanInProgress = false;
        Log.i("OUYAlaunch", "scan finished: " + reason);
    }

    private void startInitialScanIfIdle() {
        if (!beginScanIfIdle("initial-cache")) return;
        new ScanTask().execute();
    }

    private void runPendingPackageRescanIfVisible() {
        if (!hasPackageRescanPending()) return;
        if (!dataStore.isCacheBuilt()) return;
        if (isFinishing()) return;
        if (scanInProgress) {
            pendingForegroundRescan = true;
            return;
        }
        Log.i("OUYAlaunch", "package changes pending - refreshing cache now");
        rescanAppsPreserveGenres("package-change-on-resume");
    }

    private void schedulePendingPackageRescanIfNeeded() {
        rescanHandler.removeCallbacks(foregroundPackageRescanRunnable);
        if (hasPackageRescanPending()) {
            Log.i("OUYAlaunch", "stale package rescan flag ignored; manual L2+R2 scan only");
            clearPackageRescanPending();
        }
    }

    private void setupTabs() {
        tabContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < GENRES.size(); i++) {
            final int index = i;
            TextView tv = (TextView) inflater.inflate(android.R.layout.simple_list_item_1, tabContainer, false);
            tv.setText(GenreNames.getDisplayName(this, GENRES.get(i)));

            // Anwenden des Styles aus styles.xml
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                tv.setTextAppearance(R.style.TabText);
            } else {
                tv.setTextAppearance(this, R.style.TabText);
            }

            tv.setPadding(32, 0, 32, 0);
            tv.setSelected(i == currentTab);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selectTab(index);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
            tv.setLayoutParams(lp);
            tabContainer.addView(tv);
        }
        updateTabBackgrounds();
    }

    private void updateTabBackgrounds() {
        for (int i = 0; i < tabContainer.getChildCount(); i++) {
            View v = tabContainer.getChildAt(i);
            v.setBackgroundColor(i == currentTab ? getResources().getColor(R.color.gray_selected) : 0x00000000);
        }
    }

    private void selectTab(int index) {
        currentTab = index;
        updateTabBackgrounds();
        adapter.setData(filterByCurrentTab(allApps));
        recycler.post(() -> {
            // Prüfen, ob überhaupt ein View fokussiert ist
            if (recycler.getFocusedChild() == null && recycler.getChildCount() > 0) {
                recycler.getChildAt(0).requestFocus();
            }
        });

    }

    private void initTabCacheIfNeeded() {
        if (tabLists == null || tabDirty == null || tabDirty.length != GENRES.size()) {
            tabLists = new ArrayList<>();
            tabDirty = new boolean[GENRES.size()];
            for (int i = 0; i < GENRES.size(); i++) {
                tabLists.add(new ArrayList<>());
                tabDirty[i] = true;
            }
        }
    }

    private void markAllTabsDirty() {
        initTabCacheIfNeeded();
        for (int i = 0; i < tabDirty.length; i++) {
            tabDirty[i] = true;
        }
    }

    private void rebuildTabList(int tabIndex) {
        initTabCacheIfNeeded();
        if (tabIndex < 0 || tabIndex >= GENRES.size()) return;

        String genre = GENRES.get(tabIndex);
        List<AppEntry> list = tabLists.get(tabIndex);
        list.clear();

        if (allApps == null) return;

        for (AppEntry e : allApps) {
            if ("Favorites".equals(genre)) {
                if (e.favorite) list.add(e);
            } else if (genre.equals(e.genre)) {
                list.add(e);
            }
        }

        if ("Favorites".equals(genre)) {
            java.util.Collections.sort(list, new java.util.Comparator<AppEntry>() {
                @Override
                public int compare(AppEntry a, AppEntry b) {
                    String ta = (a.title != null) ? a.title.toLowerCase() : "";
                    String tb = (b.title != null) ? b.title.toLowerCase() : "";
                    return ta.compareTo(tb);
                }
            });
        }

        tabDirty[tabIndex] = false;
    }

List<AppEntry> filterByCurrentTab(List<AppEntry> ignored) {
        initTabCacheIfNeeded();

        if (tabDirty[currentTab]) {
            rebuildTabList(currentTab);
        }

        return tabLists.get(currentTab);
    }



    // ==============================
    // NEU: Löschen-Funktion
    // ==============================
    private void removeAppFromLauncher(String packageName) {
        List<AppEntry> updated = new ArrayList<>();

        for (AppEntry app : allApps) {
            if (!app.packageName.equals(packageName)) {
                updated.add(app);
            } else {
                Log.i("RemoveApp", "App entfernt: " + app.title + " (" + app.packageName + ")");
            }
        }

        allApps = updated;
        markAllTabsDirty();
        dataStore.save(allApps, true);
        adapter.setData(filterByCurrentTab(allApps));
        recycler.post(() -> {
            // Prüfen, ob überhaupt ein View fokussiert ist
            if (recycler.getFocusedChild() == null && recycler.getChildCount() > 0) {
                recycler.getChildAt(0).requestFocus();
            }
        });

    }

    // ==============================
    // NEU: Rescan-Funktion
    // ==============================
    private void rescanAppsPreserveGenres(String reason) {
        if (!beginScanIfIdle(reason)) return;
        new AsyncTask<Void, Void, List<AppEntry>>() {
            @Override
            protected void onPreExecute() {
                if (overlayLoading != null) overlayLoading.setVisibility(View.VISIBLE);
            }

            @Override
            protected List<AppEntry> doInBackground(Void... voids) {
                List<AppEntry> existing = dataStore.load();
                List<AppEntry> freshScan = AppScanner.buildCache(MainActivity.this);

                // 1) Merge: nur neue Pakete hinzufügen, bestehende unverändert lassen
                List<AppEntry> merged = new ArrayList<>(existing);
                for (AppEntry n : freshScan) {
                    boolean found = false;
                    for (AppEntry o : existing) {
                        if (o != null && n != null && o.packageName != null
                                && o.packageName.equals(n.packageName)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) merged.add(n);
                }

                // 2) Pro-Genre gruppieren (ohne putIfAbsent)
                java.util.HashMap<String, List<AppEntry>> genreMap = new java.util.HashMap<>();
                for (AppEntry e : merged) {
                    if (e == null) continue;
                    String g = (e.genre != null && e.genre.length() > 0) ? e.genre : "Casual";
                    List<AppEntry> bucket = genreMap.get(g);
                    if (bucket == null) {
                        bucket = new ArrayList<>();
                        genreMap.put(g, bucket);
                    }
                    bucket.add(e);
                }

                // 3) Null-sicherer Comparator (A–Z)
                java.util.Comparator<AppEntry> byTitleAZ = new java.util.Comparator<AppEntry>() {
                    @Override public int compare(AppEntry a, AppEntry b) {
                        String ta = (a != null && a.title != null) ? a.title : "";
                        String tb = (b != null && b.title != null) ? b.title : "";
                        return ta.toLowerCase().compareTo(tb.toLowerCase());
                    }
                };

                // 4) Innerhalb jedes Genres sortieren
                for (List<AppEntry> list : genreMap.values()) {
                    java.util.Collections.sort(list, byTitleAZ);
                }

                // 5) In feste Genre-Reihenfolge zurückführen
                List<AppEntry> sortedMerged = new ArrayList<>();
                for (String g : GENRES) {
                    List<AppEntry> bucket = genreMap.get(g);
                    if (bucket != null) sortedMerged.addAll(bucket);
                }
                // Apps mit unbekanntem Genre (falls vorhanden) ans Ende
                for (java.util.Map.Entry<String, List<AppEntry>> e : genreMap.entrySet()) {
                    if (!GENRES.contains(e.getKey())) {
                        sortedMerged.addAll(e.getValue());
                    }
                }

                dataStore.save(sortedMerged, true);
                clearPackageRescanPending();
                return sortedMerged;
            }

            @Override
            protected void onPostExecute(List<AppEntry> result) {
                try {
                    if (overlayLoading != null) overlayLoading.setVisibility(View.GONE);
                    if (result == null) {
                        result = new ArrayList<>();
                    }
                    allApps = result;
                    markAllTabsDirty();
                    adapter.setData(filterByCurrentTab(allApps));
                    markScanFinished();
                    focusFirstTileIfNeeded();
                } finally {
                    endScan(reason);
                    pendingForegroundRescan = false;
                }

            }
        }.execute();
    }


private class ScanTask extends AsyncTask<Void, Void, List<AppEntry>> {
    @Override
    protected void onPreExecute() {
        try {
            overlayLoading.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            Log.e("ScanTask", "onPreExecute failed", t);
        }
    }

    @Override
    protected List<AppEntry> doInBackground(Void... voids) {
        try {
            return AppScanner.buildCache(MainActivity.this);
        } catch (Throwable t) {
            Log.e("ScanTask", "Error in background scan", t);
            return new ArrayList<>();
        }
    }

    @Override
    protected void onPostExecute(List<AppEntry> appEntries) {
        try {
            if (overlayLoading != null)
                overlayLoading.setVisibility(View.GONE);

            if (appEntries == null) {
                Log.w("ScanTask", "appEntries is null, creating empty list");
                appEntries = new ArrayList<>();
            }

            dataStore.save(appEntries, true);
            clearPackageRescanPending();

            allApps = appEntries;
            markAllTabsDirty();

            if (adapter != null) {
                adapter.setData(filterByCurrentTab(allApps));
                markScanFinished();
                focusFirstTileIfNeeded();

            } else {
                Log.w("ScanTask", "Adapter is null!");
            }

            Log.i("ScanTask", "Finished scanning, " + appEntries.size() + " apps found");

        } catch (Throwable t) {
            Log.e("ScanTask", "onPostExecute crash", t);
        } finally {
            endScan("initial-cache");
            pendingForegroundRescan = false;
        }
    }
}

    @Override
    public void onAppLaunch(AppEntry entry) {
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(entry.packageName);
            if (launchIntent == null) {
                Intent ouyaIntent = new Intent(Intent.ACTION_MAIN, null);
                ouyaIntent.addCategory("tv.ouya.intent.category.GAME");
                ouyaIntent.setPackage(entry.packageName);
                java.util.List<android.content.pm.ResolveInfo> matches = getPackageManager().queryIntentActivities(ouyaIntent, 0);
                if (matches != null && matches.size() > 0 && matches.get(0).activityInfo != null) {
                    android.content.pm.ActivityInfo ai = matches.get(0).activityInfo;
                    launchIntent = new Intent(Intent.ACTION_MAIN);
                    launchIntent.setClassName(ai.packageName, ai.name);
                    launchIntent.addCategory("tv.ouya.intent.category.GAME");
                }
            }
            if (launchIntent != null) {
                startActivity(launchIntent);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onGenreChange(AppEntry entry) {
        int idx = GENRES.indexOf(entry.genre);
        if (idx < 0) idx = 1;
        idx++;
        if (idx >= GENRES.size()) idx = 1; // skip favorites
        entry.genre = GENRES.get(idx);
        dataStore.saveOne(entry);
        markAllTabsDirty();
        adapter.setData(filterByCurrentTab(allApps));
        recycler.post(() -> {
            // Prüfen, ob überhaupt ein View fokussiert ist
            if (recycler.getFocusedChild() == null && recycler.getChildCount() > 0) {
                recycler.getChildAt(0).requestFocus();
            }
        });

    }

    @Override
    public void onToggleFavorite(AppEntry entry) {
        entry.favorite = !entry.favorite;
        dataStore.saveOne(entry);
        markAllTabsDirty();
        if (currentTab == 0) adapter.setData(filterByCurrentTab(allApps));
        recycler.post(() -> {
            // Prüfen, ob überhaupt ein View fokussiert ist
            if (recycler.getFocusedChild() == null && recycler.getChildCount() > 0) {
                recycler.getChildAt(0).requestFocus();
            }
        });

    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BUTTON_L1) {
                selectTab(Math.max(0, currentTab - 1));
                return true;
            } else if (code == KeyEvent.KEYCODE_BUTTON_R1) {
                selectTab(Math.min(GENRES.size() - 1, currentTab + 1));
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }


    // ==============================
    // NEU: L2 + R2 für Rescan
    // ==============================
    private boolean l2Pressed = false;
    private boolean r2Pressed = false;

    private long bButtonDownTime = 0;
    private static final long LONG_PRESS_THRESHOLD = 2000; // in ms
    private boolean bPressed = false;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_L2:
                l2Pressed = true;
                if (r2Pressed && event.getRepeatCount() == 0) {
                    Log.i("MainActivity", "L2 + R2 gedrückt – starte manuellen Scan");
                    rescanAppsPreserveGenres("manual-l2-r2");
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_R2:
                r2Pressed = true;
                if (l2Pressed && event.getRepeatCount() == 0) {
                    Log.i("MainActivity", "L2 + R2 gedrückt – starte manuellen Scan");
                    rescanAppsPreserveGenres("manual-l2-r2");
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (!bPressed) { // verhindert Mehrfachauslösung
                    bPressed = true;
                    bButtonDownTime = System.currentTimeMillis();
                }
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_L2:
                l2Pressed = false;
                return true;
            case KeyEvent.KEYCODE_BUTTON_R2:
                r2Pressed = false;
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                long pressDuration = System.currentTimeMillis() - bButtonDownTime;
                bPressed = false;
                if (pressDuration >= LONG_PRESS_THRESHOLD) {
                    AppEntry selected = adapter.getSelectedApp();
                    if (selected != null) {
                        removeAppFromLauncher(selected.packageName);
                    }
                } else {
                    Log.i("MainActivity", "Kurzer Y-Druck – keine Aktion");
                }
                return true;

        }
        return super.onKeyUp(keyCode, event);
    }



    private void refreshTabTitles() {
        // Refresh tab labels from GenreNames (JSON may have changed)
        int n = Math.min(tabContainer.getChildCount(), GENRES.size());
        for (int i = 0; i < n; i++) {
            View v = tabContainer.getChildAt(i);
            if (v instanceof TextView) {
                ((TextView) v).setText(GenreNames.getDisplayName(this, GENRES.get(i)));
            }
        }
    }

    private boolean isWifiConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo wifiInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return wifiInfo != null && wifiInfo.isConnected();
    }

    private boolean isLanConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo lanInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_ETHERNET);
        return lanInfo != null && lanInfo.isConnected();
    }

    private int getWifiLevel0to4() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return 0;

        WifiInfo info = wifiManager.getConnectionInfo();
        if (info == null) return 0;

        int rssi = info.getRssi();
        int level = WifiManager.calculateSignalLevel(rssi, WIFI_ICONS.length);
        if (level < 0) level = 0;
        if (level >= WIFI_ICONS.length) level = WIFI_ICONS.length - 1;
        return level;
    }


    private void applyNetworkIconTint() {
        if (imgNetworkStatus != null) {
            int color = Color.parseColor("#D8D9DC");
            imgNetworkStatus.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }


    private void updateNetworkIcon() {
        if (imgNetworkStatus == null) {
            imgNetworkStatus = findViewById(R.id.imgNetworkStatus);
        }
        if (imgNetworkStatus == null) {
            return;
        }

        if (isLanConnected()) {
            imgNetworkStatus.setImageResource(R.drawable.ic_lan);
        } else if (isWifiConnected()) {
            int level = getWifiLevel0to4();
            imgNetworkStatus.setImageResource(WIFI_ICONS[level]);
        } else {
            imgNetworkStatus.setImageResource(R.drawable.ic_offline);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Timer stoppen, solange der Launcher nicht im Vordergrund ist
        networkHandler.removeCallbacks(networkUpdateRunnable);
        rescanHandler.removeCallbacks(foregroundPackageRescanRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reapply possible user changes to genre_names.json
        refreshTabTitles();

        // Sofort einmal aktualisieren
        updateNetworkIcon();

        // Sicherheitshalber alte Callbacks entfernen und dann neu starten
        networkHandler.removeCallbacks(networkUpdateRunnable);
        networkHandler.postDelayed(networkUpdateRunnable, NETWORK_UPDATE_INTERVAL_MS);

        // Keine automatischen Package-Rescans beim Zurückkehren aus Spielen.
        // Cache-Updates nur beim allerersten Start oder manuell per L2+R2.
        schedulePendingPackageRescanIfNeeded();
    }
}