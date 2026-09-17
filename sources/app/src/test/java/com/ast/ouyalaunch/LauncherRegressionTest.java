package com.ast.ouyalaunch;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import androidx.core.util.AtomicFile;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

/** Host regression coverage. Real OUYA timing/controller checks remain a device test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 28})
public class LauncherRegressionTest {
    private Context context;
    private DataStore store;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        store = new DataStore(context);
        new File(context.getFilesDir(), "apps.json").delete();
        new File(context.getFilesDir(), "apps.json.bak").delete();
    }

    @Test public void legacyDatabasePreservesFavoritesAndGenres() throws Exception {
        Files.write(new File(context.getFilesDir(), "apps.json").toPath(),
                ("[{\"packageName\":\"game.old\",\"title\":\"Old Game\",\"genre\":\"RPG\","
                + "\"favorite\":true,\"iconPath\":\"/old/icon.png\"}]").getBytes("UTF-8"));
        AppEntry entry = store.load().get(0);
        assertTrue(entry.favorite);
        assertEquals("RPG", entry.genre);
        assertEquals(0, entry.iconCacheVersion);
        store.save(Collections.singletonList(entry));
        assertEquals("/old/icon.png", store.load().get(0).iconPath);
    }

    @Test public void missingAndIntentionallyEmptyDatabaseAreDifferent() throws Exception {
        assertNull(store.load());
        store.save(Collections.emptyList());
        assertNotNull(store.load());
        assertTrue(store.load().isEmpty());
    }

    @Test public void interruptedWriteRestoresPreviousDatabase() throws Exception {
        store.save(Collections.singletonList(new AppEntry("game.saved", "Saved", "Action", true, null)));
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "apps.json"));
        FileOutputStream stream = file.startWrite();
        stream.write("[{broken".getBytes("UTF-8"));
        stream.close(); // Simulate process termination without finishWrite/failWrite.
        assertEquals("game.saved", store.load().get(0).packageName);
        assertTrue(store.load().get(0).favorite);
    }

    @Test public void corruptDatabaseDoesNotSilentlyBecomeEmpty() throws Exception {
        File file = new File(context.getFilesDir(), "apps.json");
        byte[] bad = "{broken".getBytes("UTF-8");
        Files.write(file.toPath(), bad);
        try { store.load(); fail("Corruption must be reported"); }
        catch (IOException expected) { }
        assertArrayEquals(bad, Files.readAllBytes(file.toPath()));
    }

    @Test public void queuedSaveSnapshotCannotBeChangedByLaterUiEdits() {
        AppEntry entry = new AppEntry("game.test", "Test", "RPG", true, null);
        entry.packageUpdatedAt = 123;
        List<AppEntry> snapshot = DataStore.snapshot(Collections.singletonList(entry));
        entry.favorite = false;
        entry.genre = "Action";
        entry.packageUpdatedAt = 999;
        assertTrue(snapshot.get(0).favorite);
        assertEquals("RPG", snapshot.get(0).genre);
        assertEquals(123, snapshot.get(0).packageUpdatedAt);
    }

    @Test public void rescanPreservesUnavailableUsbGames() throws Exception {
        AppEntry usb = new AppEntry("game.usb", "USB Game", "RPG", true, "/usb/icon.png");
        List<AppEntry> result = AppScanner.buildCache(context, Collections.singletonList(usb));
        assertEquals(1, result.size());
        assertEquals("game.usb", result.get(0).packageName);
        assertTrue(result.get(0).favorite);
        assertEquals("RPG", result.get(0).genre);
    }

    @Test public void unchangedPackageReusesExistingPreviewWithoutRewriting() throws Exception {
        File apk = new File(context.getCacheDir(), "unchanged.apk");
        Files.write(apk.toPath(), new byte[] {1, 2, 3}); // Deliberately not a ZIP: fast path must skip APK decoding.
        installGame("game.cached", apk, 456);
        File icons = new File(context.getFilesDir(), "icons");
        icons.mkdirs();
        File icon = new File(icons, "game.cached.png");
        byte[] original = {4, 5, 6};
        Files.write(icon.toPath(), original);
        AppEntry entry = new AppEntry("game.cached", "Cached", "Action", true, icon.getAbsolutePath());
        entry.apkLength = apk.length();
        entry.packageUpdatedAt = 456;
        entry.iconCacheVersion = BitmapTools.CACHE_VERSION;
        long modified = icon.lastModified();
        List<AppEntry> result = AppScanner.buildCache(context, Collections.singletonList(entry));
        assertEquals(1, result.size());
        assertArrayEquals(original, Files.readAllBytes(icon.toPath()));
        assertEquals(modified, icon.lastModified());
        assertEquals(BitmapTools.CACHE_VERSION, result.get(0).iconCacheVersion);
    }

    @Test public void scannerAcceptsOuyaOnlyCategoryAndKeepsAssignments() throws Exception {
        File apk = new File(context.getCacheDir(), "ouya-only.apk");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
            zip.putNextEntry(new ZipEntry("res/drawable-xhdpi/ouya_icon.png"));
            zip.write(png(960, 540));
            zip.closeEntry();
        }
        installGame("game.ouya", apk, 321);
        AppEntry old = new AppEntry("game.ouya", "Old", "RPG", true, null);
        List<AppEntry> result = AppScanner.buildCache(context, Collections.singletonList(old));
        assertEquals(1, result.size());
        assertTrue(result.get(0).favorite);
        assertEquals("RPG", result.get(0).genre);
        assertEquals(321, result.get(0).packageUpdatedAt);
        assertEquals(BitmapTools.CACHE_VERSION, result.get(0).iconCacheVersion);
        assertTrue(new File(result.get(0).iconPath).length() > 0);
    }

    @Test public void previewIsBoundedAndBothDecodeStreamsAreClosed() throws Exception {
        byte[] png = png(1920, 1080);
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        Bitmap preview = BitmapTools.decode(() -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(png) {
                @Override public void close() throws IOException { closes.incrementAndGet(); super.close(); }
            };
        });
        assertNotNull(preview);
        assertTrue(preview.getWidth() <= BitmapTools.WIDTH);
        assertTrue(preview.getHeight() <= BitmapTools.HEIGHT);
        assertEquals(2, opens.get());
        assertEquals(opens.get(), closes.get());
        preview.recycle();
    }

    @Test public void adapterUsesGranularUpdatesAndImmutableOldValues() {
        AppAdapter adapter = new AppAdapter(context, null);
        try {
            AppEntry first = new AppEntry("game.a", "A", "RPG", false, null);
            AppEntry second = new AppEntry("game.b", "B", "RPG", false, null);
            adapter.setData(Arrays.asList(first, second));
            AtomicInteger fullRefreshes = new AtomicInteger();
            AtomicInteger changes = new AtomicInteger();
            AtomicInteger removals = new AtomicInteger();
            adapter.registerAdapterDataObserver(new androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
                @Override public void onChanged() { fullRefreshes.incrementAndGet(); }
                @Override public void onItemRangeChanged(int start, int count, Object payload) { changes.addAndGet(count); }
                @Override public void onItemRangeRemoved(int start, int count) { removals.addAndGet(count); }
            });
            first.title = "Renamed";
            adapter.setData(Arrays.asList(first, second));
            assertEquals(1, changes.get());
            adapter.setData(Collections.singletonList(first));
            assertEquals(1, removals.get());
            assertEquals(0, fullRefreshes.get());
        } finally { adapter.close(); }
    }

    @Test public void holdingActionButtonDoesNotRepeatAction() {
        android.app.Activity activity = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger genres = new AtomicInteger();
        AtomicInteger favorites = new AtomicInteger();
        AppAdapter adapter = new AppAdapter(activity, new AppAdapter.OnAppClickListener() {
            @Override public void onAppLaunch(AppEntry entry) { launches.incrementAndGet(); }
            @Override public void onGenreChange(AppEntry entry) { genres.incrementAndGet(); }
            @Override public void onToggleFavorite(AppEntry entry) { favorites.incrementAndGet(); }
        });
        androidx.recyclerview.widget.RecyclerView recycler = new androidx.recyclerview.widget.RecyclerView(activity);
        try {
            recycler.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(activity, 3));
            recycler.setAdapter(adapter);
            activity.setContentView(recycler);
            adapter.setData(Collections.singletonList(new AppEntry("game.a", "A", "RPG", false, null)));
            recycler.measure(android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY));
            recycler.layout(0, 0, 1920, 1080);
            android.view.View tile = recycler.findViewHolderForAdapterPosition(0).itemView;
            tile.requestFocus();
            int[] keys = {android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_BUTTON_X,
                    android.view.KeyEvent.KEYCODE_BUTTON_B};
            for (int key : keys) {
                tile.dispatchKeyEvent(new android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, key, 0));
                tile.dispatchKeyEvent(new android.view.KeyEvent(0, 1, android.view.KeyEvent.ACTION_DOWN, key, 1));
                tile.dispatchKeyEvent(new android.view.KeyEvent(0, 2, android.view.KeyEvent.ACTION_DOWN, key, 2));
                tile.dispatchKeyEvent(new android.view.KeyEvent(0, 3, android.view.KeyEvent.ACTION_UP, key, 0));
            }
            assertEquals(1, launches.get());
            assertEquals(1, genres.get());
            assertEquals(1, favorites.get());
        } finally { adapter.close(); recycler.setAdapter(null); activity.finish(); }
    }

    private static byte[] png(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        bitmap.recycle();
        return stream.toByteArray();
    }

    private void installGame(String pkg, File apk, long updated) {
        PackageInfo info = new PackageInfo();
        info.packageName = pkg;
        info.lastUpdateTime = updated;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = pkg;
        info.applicationInfo.sourceDir = apk.getAbsolutePath();
        info.applicationInfo.publicSourceDir = apk.getAbsolutePath();
        info.applicationInfo.nonLocalizedLabel = "Game";
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
        ResolveInfo resolve = new ResolveInfo();
        resolve.activityInfo = new ActivityInfo();
        resolve.activityInfo.packageName = pkg;
        resolve.activityInfo.name = pkg + ".Main";
        resolve.activityInfo.applicationInfo = info.applicationInfo;
        Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(
                new Intent(Intent.ACTION_MAIN).addCategory("tv.ouya.intent.category.GAME"), resolve);
    }
}
