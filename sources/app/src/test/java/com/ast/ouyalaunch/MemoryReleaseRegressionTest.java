package com.ast.ouyalaunch;

import android.content.Context;
import android.content.ComponentCallbacks2;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcelable;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real Android lifecycle/View integration tests; require Gradle, Android SDK and Robolectric. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 28}, qualifiers = "w1920dp-h1080dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class MemoryReleaseRegressionTest {
    private Context context;

    @Before public void prepareFixture() throws Exception {
        context = RuntimeEnvironment.getApplication();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
        CategorySettings.defaults().save(context);
        File icon = new File(context.getFilesDir(), "memory-fixture.png");
        Bitmap bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff557799);
        try (FileOutputStream output = new FileOutputStream(icon)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
        List<AppEntry> entries = new ArrayList<>();
        for (int i = 0; i < 36; i++) entries.add(new AppEntry("memory.game" + i,
                String.format(Locale.ROOT, "Game %02d", i), "Casual", true, icon.getAbsolutePath()));
        new DataStore(context).save(entries);
    }

    @Test public void fullStopClearsCacheBackgroundAndAllHolderImagesWithoutLosingState() throws Exception {
        ActivityController<MainActivity> owner = start();
        boolean stopped = false;
        try {
            MainActivity activity = owner.get();
            RecyclerView recycler = recycler(activity);
            GridLayoutManager grid = (GridLayoutManager) recycler.getLayoutManager();
            recycler.scrollBy(0, 288); frames(activity, 12);
            assertTrue(grid.findViewByPosition(6).requestFocus()); frames(activity, 8);
            AppAdapter adapter = adapter(activity);
            int first = grid.findFirstVisibleItemPosition();
            String focused = adapter.getSelectedPackage();
            Bitmap displayed = ((BitmapDrawable) ((ImageView) grid.findViewByPosition(first)
                    .findViewById(R.id.img)).getDrawable()).getBitmap();
            byte[] diskCache = java.nio.file.Files.readAllBytes(new File(context.getFilesDir(), "memory-fixture.png").toPath());
            owner.pause().stop(); stopped = true;
            assertReleased(activity);
            assertEquals(36, adapter.getItemCount());
            assertEquals(focused, adapter.getSelectedPackage());
            assertEquals(first, ((int[]) field(activity, "tabFirstVisible"))[0]);
            assertNotNull(((Parcelable[]) field(activity, "tabScroll"))[0]);
            assertFalse("Published pixels must not be force-recycled", displayed.isRecycled());
            assertArrayEquals(diskCache, java.nio.file.Files.readAllBytes(new File(context.getFilesDir(), "memory-fixture.png").toPath()));
        } finally {
            if (!stopped) owner.pause().stop();
            owner.destroy();
        }
    }

    @Test public void returnReloadsVisibleImagesAndRetainsExactRowAndFocus() throws Exception {
        ActivityController<MainActivity> owner = start();
        try {
            MainActivity activity = owner.get();
            RecyclerView recycler = recycler(activity);
            GridLayoutManager grid = (GridLayoutManager) recycler.getLayoutManager();
            recycler.scrollBy(0, 576); frames(activity, 12);
            assertTrue(grid.findViewByPosition(9).requestFocus()); frames(activity, 8);
            int first = grid.findFirstVisibleItemPosition();
            int inset = grid.findViewByPosition(first).getTop();
            String focused = adapter(activity).getSelectedPackage();
            owner.pause().stop(); assertReleased(activity);
            owner.restart().start().resume().visible();
            awaitSettled(activity);
            assertFalse((Boolean) field(activity, "uiResourcesReleased"));
            assertNotNull(((ImageView) activity.findViewById(R.id.bg)).getDrawable());
            assertEquals(first, grid.findFirstVisibleItemPosition());
            assertEquals(inset, grid.findViewByPosition(first).getTop());
            assertEquals(focused, adapter(activity).getSelectedPackage());
            assertEquals(0f, recycler.getTranslationX(), 0f);
            assertEquals(1f, recycler.getAlpha(), .01f);
            assertVisibleImages(activity);
        } finally { owner.pause().stop().destroy(); }
    }

    @Test public void pauseOnlyKeepsBackdropAndTileBitmaps() throws Exception {
        ActivityController<MainActivity> owner = start();
        boolean paused = false;
        try {
            MainActivity activity = owner.get();
            ImageView backdrop = activity.findViewById(R.id.bg);
            ImageView tile = recycler(activity).findViewHolderForAdapterPosition(0).itemView.findViewById(R.id.img);
            Object oldBackdrop = backdrop.getDrawable(), oldTile = tile.getDrawable();
            owner.pause(); paused = true;
            assertFalse((Boolean) field(activity, "uiResourcesReleased"));
            assertSame(oldBackdrop, backdrop.getDrawable());
            assertSame(oldTile, tile.getDrawable());
            owner.resume(); paused = false;
            frames(activity, 24);
            assertSame(oldBackdrop, backdrop.getDrawable());
        } finally {
            if (!paused) owner.pause();
            owner.stop().destroy();
        }
    }

    @Test public void uiHiddenBeforeOnStopIsIdempotentAndDoesNotOverwriteSavedViewport() throws Exception {
        ActivityController<MainActivity> owner = start();
        boolean stopped = false;
        try {
            MainActivity activity = owner.get();
            recycler(activity).scrollBy(0, 288); frames(activity, 12);
            owner.pause();
            activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
            assertReleased(activity);
            Parcelable saved = ((Parcelable[]) field(activity, "tabScroll"))[0];
            owner.stop(); stopped = true;
            activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
            activity.onLowMemory();
            assertReleased(activity);
            assertSame(saved, ((Parcelable[]) field(activity, "tabScroll"))[0]);
        } finally {
            if (!stopped) owner.pause().stop();
            owner.destroy();
        }
    }

    @Test public void returnAfterInterruptedTabLoadFinishesTheRequestedDestination() throws Exception {
        ActivityController<MainActivity> owner = start();
        try {
            MainActivity activity = owner.get();
            press(activity, KeyEvent.KEYCODE_BUTTON_R1);
            assertTrue((Boolean) field(activity, "preparingPage"));
            owner.pause().stop();
            assertEquals(1, field(activity, "deferredTab"));
            assertReleased(activity);
            owner.restart().start().resume().visible();
            awaitSettled(activity);
            assertEquals(1, field(activity, "currentTab"));
            assertFalse((Boolean) field(activity, "pageNeedsReload"));
            assertVisibleImages(activity);
        } finally { owner.pause().stop().destroy(); }
    }

    @Test public void rapidReverseDuringColdReturnDoesNotExposeAnEmptyOldPage() throws Exception {
        ActivityController<MainActivity> owner = start();
        try {
            MainActivity activity = owner.get();
            owner.pause().stop();
            owner.restart().start().resume().visible();
            press(activity, KeyEvent.KEYCODE_BUTTON_R1);
            press(activity, KeyEvent.KEYCODE_BUTTON_L1);
            assertTrue("Cold reverse must wait for its images, not use the warm-page shortcut",
                    (Boolean) field(activity, "preparingPage"));
            awaitSettled(activity);
            assertEquals(0, field(activity, "currentTab"));
            assertVisibleImages(activity);
        } finally { owner.pause().stop().destroy(); }
    }

    @Test public void latePresentationAndBindCannotResurrectAStoppedUi() throws Exception {
        ActivityController<MainActivity> owner = start();
        boolean stopped = false;
        try {
            MainActivity activity = owner.get();
            AppAdapter adapter = adapter(activity);
            AppAdapter.VH holder = (AppAdapter.VH) recycler(activity).findViewHolderForAdapterPosition(0);
            owner.pause().stop(); stopped = true;
            Method present = MainActivity.class.getDeclaredMethod("presentPage", int.class, boolean.class);
            present.setAccessible(true); present.invoke(activity, 1, true);
            adapter.invalidateIcons();
            adapter.onBindViewHolder(holder, 0); adapter.onViewAttachedToWindow(holder);
            frames(activity, 4);
            assertReleased(activity);
            assertEquals(1, field(activity, "deferredTab"));
            assertFalse((Boolean) field(activity, "preparingPage"));
            Bundle saved = new Bundle(); activity.onSaveInstanceState(saved);
            assertEquals(1, saved.getInt("tab"));
        } finally {
            if (!stopped) owner.pause().stop();
            owner.destroy();
        }
    }

    @Test public void repeatedReturnsAndEmptyTabsRemainUsable() throws Exception {
        ActivityController<MainActivity> owner = start();
        try {
            MainActivity activity = owner.get();
            for (int i = 0; i < 3; i++) {
                owner.pause().stop(); assertReleased(activity);
                owner.restart().start().resume().visible(); awaitSettled(activity);
                assertVisibleImages(activity);
            }
            press(activity, KeyEvent.KEYCODE_BUTTON_R1); awaitSettled(activity);
            press(activity, KeyEvent.KEYCODE_BUTTON_R1); awaitSettled(activity);
            assertEquals(0, adapter(activity).getItemCount()); // Action is intentionally empty.
            owner.pause().stop();
            owner.restart().start().resume().visible(); awaitSettled(activity);
            assertEquals(2, field(activity, "currentTab"));
            assertEquals(0, adapter(activity).getItemCount());
            assertEquals(1f, recycler(activity).getAlpha(), .01f);
        } finally { owner.pause().stop().destroy(); }
    }

    private ActivityController<MainActivity> start() throws Exception {
        ActivityController<MainActivity> owner = Robolectric.buildActivity(MainActivity.class).setup().visible();
        awaitSettled(owner.get());
        return owner;
    }
    private static void assertVisibleImages(MainActivity activity) {
        RecyclerView recycler = recycler(activity);
        GridLayoutManager grid = (GridLayoutManager) recycler.getLayoutManager();
        assertTrue(grid.findFirstVisibleItemPosition() >= 0);
        for (int i = grid.findFirstVisibleItemPosition(); i <= grid.findLastVisibleItemPosition(); i++) {
            View tile = grid.findViewByPosition(i);
            assertNotNull(tile);
            assertNotNull("Visible tile image missing at " + i, ((ImageView) tile.findViewById(R.id.img)).getDrawable());
        }
    }
    private static void assertReleased(MainActivity activity) throws Exception {
        assertTrue((Boolean) field(activity, "uiResourcesReleased"));
        assertTrue((Boolean) field(activity, "pageNeedsReload"));
        assertNull(((ImageView) activity.findViewById(R.id.bg)).getDrawable());
        AppAdapter adapter = adapter(activity);
        IconLoader loader = (IconLoader) field(adapter, "icons");
        synchronized (loader) {
            assertTrue((Boolean) field(loader, "paused"));
            assertTrue((Boolean) field(loader, "uiReleased"));
            assertEquals(0, ((android.util.LruCache<?, ?>) field(loader, "cache")).size());
            for (String name : new String[] {"jobs", "prepared"}) assertTrue(((Map<?, ?>) field(loader, name)).isEmpty());
            for (String name : new String[] {"queue", "awaiting", "rowPrefetch", "tabPrefetch"})
                assertTrue(((Collection<?>) field(loader, name)).isEmpty());
            assertNull(field(loader, "preparedCallback"));
        }
        for (Object object : (Set<?>) field(adapter, "holders")) {
            AppAdapter.VH holder = (AppAdapter.VH) object;
            assertNull("Also clear cached/pooled holders", holder.img.getDrawable());
            assertNull(holder.img.getTag());
        }
    }
    private static AppAdapter adapter(MainActivity activity) throws Exception { return (AppAdapter) field(activity, "adapter"); }
    private static RecyclerView recycler(MainActivity activity) { return activity.findViewById(R.id.recycler); }
    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }
    private static void press(MainActivity activity, int key) {
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }
    private static void awaitSettled(MainActivity activity) throws Exception {
        for (int i = 0; i < 400; i++) {
            frames(activity, 1);
            if ((Boolean) field(activity, "loaded") && !(Boolean) field(activity, "preparingPage")
                    && !(Boolean) field(activity, "pageEntering")
                    && !(Boolean) field(activity, "pageNeedsReload")) return;
            Thread.sleep(2);
        }
        fail("Page did not settle after release/resume");
    }
    private static void frames(MainActivity activity, int count) {
        for (int i = 0; i < count; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS);
            View root = activity.getWindow().getDecorView();
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1920, 1080);
            root.getViewTreeObserver().dispatchOnPreDraw();
        }
    }
}
