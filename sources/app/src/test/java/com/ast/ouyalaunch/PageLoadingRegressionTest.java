package com.ast.ouyalaunch;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real Android/Robolectric API checks. Run with :app:testDebugUnitTest, not host doubles. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 28}, qualifiers = "w1920dp-h1080dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class PageLoadingRegressionTest {
    private Context context;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
    }

    @Test public void onlyVisibleIconsParticipateInPageBarrier() throws Exception {
        GateDecoder d = new GateDecoder(null); IconLoader l = new IconLoader(d, 24 * 1024 * 1024);
        AtomicInteger ready = new AtomicInteger();
        try {
            List<AppEntry> entries = entries("page", 15);
            PageLoadWindow w = PageLoadWindow.forViewport(15, 3, 0, -1, 878, 6, 288);
            l.prepare(entries, w.first, w.count, () -> {
                for (int i = 0; i < 9; i++) {
                    ImageView image = new ImageView(context); l.load(image, entries.get(i).iconPath);
                    assertNotNull("Prepared visible icons must bind together", image.getDrawable());
                }
                ready.incrementAndGet();
            });
            await(() -> ready.get() == 1); idle(l);
            assertEquals(9, d.seen.size());
        } finally { l.close(); }
    }

    @Test public void visibleLoadPromotesPredictedImageAndPreservesBothWarmupScopes() throws Exception {
        GateDecoder d = new GateDecoder("block"); IconLoader l = new IconLoader(d, 24 * 1024 * 1024);
        ImageView block = new ImageView(context), visible = new ImageView(context);
        try {
            l.load(block, "block"); assertTrue(d.entered.await(3, TimeUnit.SECONDS));
            l.prefetchPage(entries("tab", 20), 6, 9);
            l.prefetch(entries("row", 9), 0, 1);
            l.load(visible, "tab10");
            d.release.countDown(); idle(l);
            assertEquals("tab10", d.seen.get(1));
            assertEquals(1, Collections.frequency(d.seen, "tab10"));
            assertEquals(19, d.seen.size());
            assertNotNull(visible.getDrawable());
            for (int i = 0; i < 9; i++) assertEquals("row" + i, d.seen.get(2 + i));
        } finally { d.release.countDown(); l.close(); }
    }

    @Test public void pageChangeCancelsObsoletePredictionsWithoutWaitingForThem() throws Exception {
        GateDecoder d = new GateDecoder("block"); IconLoader l = new IconLoader(d, 24 * 1024 * 1024);
        ImageView block = new ImageView(context); AtomicInteger ready = new AtomicInteger();
        try {
            l.load(block, "block"); assertTrue(d.entered.await(3, TimeUnit.SECONDS));
            l.prefetchPage(entries("next", 9), 0, 9); l.prefetch(entries("oldrows", 9), 0, 1);
            l.prepare(entries("next", 9), 0, 3, ready::incrementAndGet);
            d.release.countDown(); await(() -> ready.get() == 1); idle(l);
            assertEquals(4, d.seen.size());
            assertTrue(d.seen.contains("next0")); assertTrue(d.seen.contains("next1")); assertTrue(d.seen.contains("next2"));
        } finally { d.release.countDown(); l.close(); }
    }

    @Test public void lowMemoryDoesNotRefillCacheWithInflightSpeculation() throws Exception {
        GateDecoder d = new GateDecoder("tab0"); IconLoader l = new IconLoader(d, 24 * 1024 * 1024);
        try {
            l.prefetchPage(entries("tab", 9), 0, 9); assertTrue(d.entered.await(3, TimeUnit.SECONDS));
            l.trim(true); l.prefetchPage(entries("other", 9), 0, 9);
            d.release.countDown(); idle(l);
            assertEquals(1, d.seen.size());
            assertEquals(0, ((android.util.LruCache<?, ?>) field(l, "cache")).size());
            ImageView visible = new ImageView(context); l.load(visible, "tab0");
            await(() -> visible.getDrawable() != null); idle(l);
            assertEquals(2, d.seen.size());
        } finally { d.release.countDown(); l.close(); }
    }

    @Test public void previewUsesOneOpenWithoutChangingDimensionsOrAlpha() throws Exception {
        CountingSource fast = png(484, 273), baseline = new CountingSource(fast.bytes);
        Bitmap a = BitmapTools.decodeCachedPreview(fast), b = BitmapTools.decode(baseline);
        assertNotNull(a); assertNotNull(b);
        assertEquals(1, fast.opens); assertEquals(1, fast.closes);
        assertEquals(2, baseline.opens); assertEquals(2, baseline.closes);
        assertEquals(b.getWidth(), a.getWidth()); assertEquals(b.getHeight(), a.getHeight());
        assertEquals(b.getConfig(), a.getConfig());
        assertEquals(b.getPixel(20, 20), a.getPixel(20, 20));
        a.recycle(); b.recycle();
    }

    @Test public void oversizedCacheEntryKeepsTwoPassDownsampling() throws Exception {
        CountingSource source = png(1200, 800);
        Bitmap bitmap = BitmapTools.decodeCachedPreview(source);
        assertNotNull(bitmap);
        assertTrue(bitmap.getWidth() <= 484); assertTrue(bitmap.getHeight() <= 273);
        assertEquals(2, source.opens); assertEquals(2, source.closes);
        bitmap.recycle();
    }

    @Test public void malformedPreviewDoesNotLeakItsStream() throws Exception {
        CountingSource source = new CountingSource(new byte[] {1, 2, 3});
        assertNull(BitmapTools.decodeCachedPreview(source));
        assertEquals(source.opens, source.closes);
    }

    @Test public void nextTabWarmsOnlyAfterFadeSkipsHiddenTabAndUsesSavedWindow() throws Exception {
        boolean[] enabled = {true, false, true, true, true, true, true};
        new CategorySettings(MainActivity.GENRES.toArray(new String[0]), enabled).save(context);
        List<AppEntry> action = entries("action", 20);
        for (int i = 0; i < action.size(); i++) {
            action.get(i).genre = "Action";
            action.get(i).title = String.format(Locale.ROOT, "Game %02d", i);
            // Missing files are intentional: warmup must remain non-blocking even on failure.
            action.get(i).iconPath = new java.io.File(context.getCacheDir(), "not-present-action-" + i).getAbsolutePath();
        }
        new DataStore(context).save(action);
        ActivityController<MainActivity> owner = Robolectric.buildActivity(MainActivity.class).setup().visible();
        boolean paused = false;
        try {
            MainActivity activity = owner.get();
            for (int attempt = 0; attempt < 300; attempt++) {
                frames(activity, 1);
                if ((Boolean) field(activity, "loaded") && !(Boolean) field(activity, "preparingPage")) break;
                Thread.sleep(2);
            }
            assertTrue((Boolean)field(activity, "pageEntering"));
            ((int[])field(activity, "tabFirstVisible"))[2] = 6;
            ((String[])field(activity, "tabFocus"))[2] = "action12";
            AppAdapter adapter = (AppAdapter)field(activity, "adapter");
            IconLoader icons = (IconLoader)field(adapter, "icons");
            invoke(activity, "prefetchNextTab");
            assertTrue(((Set<?>)field(icons, "tabPrefetch")).isEmpty());
            frames(activity, 24);
            Set<String> expected = new HashSet<>();
            for (int i = 6; i < 15; i++) expected.add(action.get(i).iconPath);
            assertEquals(expected, field(icons, "tabPrefetch"));
            assertEquals(0, ((RecyclerView)activity.findViewById(R.id.recycler)).getAdapter().getItemCount());
            assertEquals(1f, activity.findViewById(R.id.recycler).getAlpha(), .01f);
            owner.pause(); paused = true;
            assertTrue(((Set<?>)field(icons, "tabPrefetch")).isEmpty());
        } finally {
            if (!paused) owner.pause();
            owner.stop().destroy();
        }
    }

    private static final class GateDecoder implements IconLoader.Decoder {
        final String gate;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final List<String> seen = Collections.synchronizedList(new ArrayList<>());
        GateDecoder(String gate) { this.gate = gate; }
        @Override public Bitmap decode(String path) throws Exception {
            seen.add(path);
            if (path.equals(gate)) {
                entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("gate timeout");
            }
            return Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
        }
    }
    private static final class CountingSource implements BitmapTools.Source {
        final byte[] bytes; int opens, closes;
        CountingSource(byte[] bytes) { this.bytes = bytes; }
        @Override public InputStream open() {
            opens++;
            return new ByteArrayInputStream(bytes) {
                private boolean closed;
                @Override public void close() throws IOException {
                    if (!closed) { closes++; closed = true; }
                    super.close();
                }
            };
        }
    }
    private static CountingSource png(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0x80336699);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)); bitmap.recycle();
        return new CountingSource(bytes.toByteArray());
    }
    private static List<AppEntry> entries(String prefix, int count) {
        List<AppEntry> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(new AppEntry(prefix + i, prefix + i, "Casual", false, prefix + i));
        return result;
    }
    private interface Condition { boolean ready() throws Exception; }
    private static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (condition.ready()) return;
            Thread.sleep(2);
        } while (System.nanoTime() < deadline);
        fail("Timed out waiting for image loading");
    }
    private static void idle(IconLoader loader) throws Exception { await(() -> { synchronized (loader) {
        return ((Map<?, ?>)field(loader, "jobs")).isEmpty();
    }}); }
    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
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
