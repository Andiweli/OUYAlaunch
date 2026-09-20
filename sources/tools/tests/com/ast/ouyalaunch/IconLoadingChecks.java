package com.ast.ouyalaunch;

import android.graphics.Bitmap;
import android.os.Handler;
import android.util.LruCache;
import android.widget.ImageView;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/** Runs REAL IconLoader/BitmapTools code against explicit, limited host Android doubles. */
public final class IconLoadingChecks {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        visiblePageDoesNotWaitForOffscreenRows();
        bothPrefetchScopesSurviveAndRowsHavePriority();
        visibleRequestPromotesPredictionWithoutDuplicateDecode();
        newPreparationDropsObsoleteSpeculation();
        replacedPreparationRejectsOldCallback();
        replacedPredictionOnlyLoadsNewestTab();
        memoryPressureDropsInflightSpeculation();
        predictionRespectsMemoryBudgetAndDoesNotPinImages();
        pausedWorkerKeepsForegroundButSkipsSpeculation();
        invalidationRejectsInflightOldImage();
        failedIconDoesNotBlockPage();
        cachedPreviewOpensOnceAndKeepsPixels();
        legacyAndOversizedImagesKeepSafeFallback();
        corruptOrThrowingStreamsClose();
        System.out.println("IconLoadingChecks: 14 scenarios, " + assertions + " assertions passed (HOST DOUBLES)");
    }

    private static void visiblePageDoesNotWaitForOffscreenRows() throws Exception {
        GateDecoder d = new GateDecoder(null); IconLoader l = loader(d); AtomicInteger ready = new AtomicInteger();
        try {
            List<AppEntry> entries = entries("page", 15);
            PageLoadWindow w = PageLoadWindow.forViewport(15, 3, 0, -1, 878, 6, 288);
            l.prepare(entries, w.first, w.count, () -> {
                for (int i = 0; i < 9; i++) {
                    ImageView view = new ImageView(null); l.load(view, entries.get(i).iconPath);
                    check(view.getDrawable() != null, "all visible icons bind immediately");
                }
                ready.incrementAndGet();
            });
            await(() -> ready.get() == 1); idle(l);
            check(d.seen.size() == 9, "only nine visible icons decoded, not fifteen");
        } finally { l.close(); }
    }

    private static void bothPrefetchScopesSurviveAndRowsHavePriority() throws Exception {
        GateDecoder d = new GateDecoder("block"); IconLoader l = loader(d);
        try {
            block(l, d);
            l.prefetchPage(entries("tab", 20), 6, 9);
            l.prefetch(entries("row", 12), 0, 1);
            d.release.countDown(); idle(l);
            check(d.seen.size() == 19, "one blocking request plus nine rows plus nine next-tab icons");
            for (int i = 0; i < 9; i++) {
                check(d.seen.get(1 + i).equals("row" + i), "nearby rows before next tab");
                check(d.seen.get(10 + i).equals("tab" + (6 + i)), "saved next-tab window retained");
            }
        } finally { d.release.countDown(); l.close(); }
    }

    private static void visibleRequestPromotesPredictionWithoutDuplicateDecode() throws Exception {
        GateDecoder d = new GateDecoder("block"); IconLoader l = loader(d);
        try {
            block(l, d); l.prefetchPage(entries("tab", 9), 0, 9); l.prefetch(entries("row", 9), 0, 1);
            ImageView visible = new ImageView(null); l.load(visible, "tab5");
            d.release.countDown(); idle(l);
            check("tab5".equals(d.seen.get(1)), "visible promotion takes priority");
            check(Collections.frequency(d.seen, "tab5") == 1, "prediction decoded only once");
            check(visible.getDrawable() != null, "visible target bound");
        } finally { d.release.countDown(); l.close(); }
    }

    private static void newPreparationDropsObsoleteSpeculation() throws Exception {
        GateDecoder d = new GateDecoder("block"); IconLoader l = loader(d); AtomicInteger ready = new AtomicInteger();
        try {
            block(l, d); l.prefetchPage(entries("next", 9), 0, 9); l.prefetch(entries("row", 9), 0, 1);
            l.prepare(entries("next", 9), 0, 3, ready::incrementAndGet);
            d.release.countDown(); await(() -> ready.get() == 1); idle(l);
            check(d.seen.size() == 4, "no obsolete speculative work delays new page");
            check(d.seen.containsAll(Arrays.asList("block", "next0", "next1", "next2")), "only required paths");
        } finally { d.release.countDown(); l.close(); }
    }

    private static void replacedPreparationRejectsOldCallback() throws Exception {
        GateDecoder d = new GateDecoder(null); IconLoader l = loader(d);
        AtomicInteger old = new AtomicInteger(), latest = new AtomicInteger();
        try {
            l.setPaused(true); l.prepare(entries("old", 9), 0, 9, old::incrementAndGet);
            l.prepare(entries("latest", 9), 0, 9, latest::incrementAndGet); l.setPaused(false);
            await(() -> latest.get() == 1); idle(l);
            check(old.get() == 0 && d.seen.size() == 9, "cancelled preparation never publishes");
            for (String path : d.seen) check(path.startsWith("latest"), "old queued preparation removed");
        } finally { l.close(); }
    }

    private static void replacedPredictionOnlyLoadsNewestTab() throws Exception {
        GateDecoder d = new GateDecoder("block"); IconLoader l = loader(d);
        try {
            block(l, d); l.prefetchPage(entries("old", 9), 0, 9); l.prefetchPage(entries("new", 9), 0, 9);
            d.release.countDown(); idle(l);
            check(d.seen.size() == 10, "only one predicted tab");
            for (int i = 1; i < d.seen.size(); i++) check(d.seen.get(i).startsWith("new"), "obsolete next-tab jobs removed");
        } finally { d.release.countDown(); l.close(); }
    }

    private static void memoryPressureDropsInflightSpeculation() throws Exception {
        GateDecoder d = new GateDecoder("tab0"); IconLoader l = loader(d);
        try {
            l.prefetchPage(entries("tab", 9), 0, 9); check(d.entered.await(3, TimeUnit.SECONDS), "speculation in flight");
            l.trim(true); l.prefetch(entries("row", 9), 0, 1); d.release.countDown(); idle(l);
            check(d.seen.size() == 1, "trim drops queued speculation");
            check(((LruCache<?, ?>) field(l, "cache")).size() == 0, "inflight speculative result does not refill trimmed cache");
            ImageView visible = new ImageView(null); l.load(visible, "tab0");
            await(() -> visible.getDrawable() != null); idle(l);
            check(d.seen.size() == 2, "foreground still works after trim");
        } finally { d.release.countDown(); l.close(); }
    }

    private static void predictionRespectsMemoryBudgetAndDoesNotPinImages() throws Exception {
        GateDecoder d = new GateDecoder(null); IconLoader l = new IconLoader(d, 2 * 1024 * 1024);
        try {
            l.prefetchPage(entries("tab", 40), 0, 40); idle(l);
            check(d.seen.size() == 1, "small cache reduces speculative window");
            check(((Set<?>)field(l, "tabPrefetch")).size() == 1, "bounded prediction metadata");
            check(((Map<?, ?>)field(l, "prepared")).isEmpty(), "predictions do not pin bitmaps outside LRU");
            check(((LruCache<?, ?>)field(l, "cache")).size() <= 2 * 1024 * 1024, "same byte budget");
        } finally { l.close(); }
    }

    private static void pausedWorkerKeepsForegroundButSkipsSpeculation() throws Exception {
        GateDecoder d = new GateDecoder(null); IconLoader l = loader(d); AtomicInteger ready = new AtomicInteger();
        try {
            l.setPaused(true); l.prefetchPage(entries("tab", 9), 0, 9);
            l.prepare(entries("visible", 3), 0, 3, ready::incrementAndGet);
            check(d.seen.isEmpty(), "paused worker performs no new reads");
            l.setPaused(false); await(() -> ready.get() == 1); idle(l);
            check(d.seen.size() == 3, "resume keeps foreground preparation only");
        } finally { l.close(); }
    }

    private static void invalidationRejectsInflightOldImage() throws Exception {
        GateDecoder d = new GateDecoder("block"); IconLoader l = loader(d); AtomicInteger ready = new AtomicInteger();
        try {
            ImageView obsolete = new ImageView(null); l.load(obsolete, "block");
            check(d.entered.await(3, TimeUnit.SECONDS), "old generation in flight");
            l.invalidate(); l.prepare(entries("fresh", 1), 0, 1, ready::incrementAndGet);
            d.release.countDown(); await(() -> ready.get() == 1); idle(l);
            check(obsolete.getDrawable() == null, "old generation does not bind");
            check(d.seen.contains("fresh0"), "fresh generation completes");
        } finally { d.release.countDown(); l.close(); }
    }

    private static void failedIconDoesNotBlockPage() throws Exception {
        IconLoader l = new IconLoader(path -> {
            if (path.startsWith("bad")) throw new IOException("missing");
            return Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
        }, 8 * 1024 * 1024); AtomicInteger ready = new AtomicInteger();
        try {
            List<AppEntry> e = entries("bad", 1); e.addAll(entries("good", 1));
            l.prepare(e, 0, e.size(), ready::incrementAndGet);
            await(() -> ready.get() == 1); idle(l); check(ready.get() == 1, "failed icon cannot strand callback");
        } finally { l.close(); }
    }

    private static void cachedPreviewOpensOnceAndKeepsPixels() throws Exception {
        byte[] png = imageBytes(484, 273, "png");
        CountingSource fast = new CountingSource(png), old = new CountingSource(png);
        Bitmap a = BitmapTools.decodeCachedPreview(fast), b = BitmapTools.decode(old);
        check(a != null && b != null, "preview decoded");
        check(fast.opens == 1 && fast.closes == 1, "fast preview opens and closes once");
        check(old.opens == 2 && old.closes == 2, "original decoder remains two-pass");
        check(a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight(), "same dimensions");
        for (int y = 0; y < 273; y += 9) for (int x = 0; x < 484; x += 9)
            check(a.getPixel(x, y) == b.getPixel(x, y), "same software pixels/alpha in host double");
    }

    private static void legacyAndOversizedImagesKeepSafeFallback() throws Exception {
        for (String format : new String[] {"png", "jpg"}) {
            CountingSource source = new CountingSource(imageBytes(1000, 700, format));
            Bitmap image = BitmapTools.decodeCachedPreview(source);
            check(image != null && image.getWidth() <= 484 && image.getHeight() <= 273, "legacy image downsampled");
            check(source.opens == 2 && source.closes == 2, "legacy fallback still only two opens");
        }
    }

    private static void corruptOrThrowingStreamsClose() throws Exception {
        CountingSource invalid = new CountingSource(new byte[] {1, 2, 3});
        check(BitmapTools.decodeCachedPreview(invalid) == null, "corrupt cache image skipped");
        check(invalid.opens == invalid.closes, "corrupt stream closed");
        AtomicInteger closed = new AtomicInteger();
        try {
            BitmapTools.decodeCachedPreview(() -> new InputStream() {
                @Override public int read() throws IOException { throw new IOException("read failed"); }
                @Override public void close() { closed.incrementAndGet(); }
            });
            throw new AssertionError("expected IOException");
        } catch (IOException expected) { check(closed.get() == 1, "throwing stream closed"); }
    }

    private static final class CountingSource implements BitmapTools.Source {
        final byte[] bytes; int opens, closes;
        CountingSource(byte[] bytes) { this.bytes = bytes; }
        @Override public InputStream open() {
            opens++;
            return new ByteArrayInputStream(bytes) {
                private boolean closed;
                @Override public void close() throws IOException { if (!closed) { closes++; closed = true; } super.close(); }
            };
        }
    }
    private static byte[] imageBytes(int width, int height, String format) throws Exception {
        BufferedImage image = new BufferedImage(width, height,
                "jpg".equals(format) ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y,
                (((x + y) % 256) << 24) | ((x % 256) << 16) | ((y % 256) << 8) | 123);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        check(ImageIO.write(image, format, out), "fixture encoded"); return out.toByteArray();
    }
    private static IconLoader loader(GateDecoder d) { return new IconLoader(d, 24 * 1024 * 1024); }
    private static void block(IconLoader l, GateDecoder d) throws Exception {
        l.load(new ImageView(null), "block"); check(d.entered.await(3, TimeUnit.SECONDS), "decoder blocked deterministically");
    }
    private static List<AppEntry> entries(String prefix, int count) {
        List<AppEntry> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(new AppEntry(prefix + i, prefix + i, "Casual", false, prefix + i));
        return list;
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
    private interface Condition { boolean ready() throws Exception; }
    private static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            Handler.drainMainQueue();
            if (condition.ready()) return;
            if (System.nanoTime() >= deadline) throw new AssertionError("Timed out waiting for worker");
            Thread.sleep(1);
        }
    }
    private static void idle(IconLoader l) throws Exception { await(() -> { synchronized (l) {
        return ((Map<?, ?>)field(l, "jobs")).isEmpty();
    }}); }
    private static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }
}
