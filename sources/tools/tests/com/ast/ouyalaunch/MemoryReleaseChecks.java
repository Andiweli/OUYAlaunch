package com.ast.ouyalaunch;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real IconLoader code; Android View/Looper/Bitmap behaviour is explicitly mocked. */
public final class MemoryReleaseChecks {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        releaseDropsCacheAndPinnedPageWithoutRecyclingPublishedImages();
        inflightVisibleImageCannotReturnAfterRelease();
        pendingMainDeliveryIsRemovedButOtherHandlersSurvive();
        cancelledPartialPageNeverPublishes();
        hiddenLoadsCannotRestartTheQueueEvenAfterInvalidation();
        immediateResumeOfSamePathRejectsPreviousGeneration();
        cancelledPostedDeliveryIsSafeEvenIfExecutedLate();
        returningPageStillBindsAllVisibleImagesTogether();
        pauseWithoutStopKeepsImagesAndForegroundRequests();
        runningMemoryWarningDoesNotRefillTheCache();
        repeatedStopResumeDoesNotAccumulateImagesOrJobs();
        closingAReleasedLoaderCannotRestartIt();
        System.out.println("MemoryReleaseChecks: 12 scenarios, " + assertions + " assertions passed (HOST DOUBLES)");
    }

    private static void releaseDropsCacheAndPinnedPageWithoutRecyclingPublishedImages() throws Exception {
        GateDecoder decoder = new GateDecoder(null);
        IconLoader loader = loader(decoder);
        ImageView visible = new ImageView(null);
        AtomicInteger ready = new AtomicInteger();
        try {
            loader.prepare(entries("page", 9), 0, 9, ready::incrementAndGet);
            await(() -> ready.get() == 1);
            loader.load(visible, "page0");
            Bitmap shared = (Bitmap) visible.getDrawable();
            check(shared != null, "prepared page binds from cache");
            check(cache(loader).size() > 0, "cache is populated before stop");
            check(!((Map<?, ?>) field(loader, "prepared")).isEmpty(), "prepared images pinned before stop");
            loader.releaseUiMemory();
            assertReleased(loader);
            check(!shared.isRecycled(), "published image is never manually recycled");
            IconLoader.clear(visible); // AppAdapter performs this for EVERY holder.
            check(visible.getDrawable() == null && visible.getTag() == null, "view reference and tag cleared");
            check(ready.get() == 1, "release never reruns preparation callback");
        } finally { loader.close(); }
    }

    private static void inflightVisibleImageCannotReturnAfterRelease() throws Exception {
        GateDecoder decoder = new GateDecoder("slow");
        IconLoader loader = loader(decoder);
        ImageView visible = new ImageView(null);
        try {
            loader.load(visible, "slow"); decoder.waitUntilBlocked();
            loader.load(new ImageView(null), "queued");
            loader.prefetchPage(entries("predicted", 9), 0, 9);
            loader.releaseUiMemory(); IconLoader.clear(visible);
            decoder.release.countDown();
            await(() -> staleRecycled(loader, decoder));
            assertReleased(loader);
            check(decoder.calls.size() == 1, "no queued/offscreen work runs after stop");
            check(visible.getDrawable() == null, "inflight image cannot reattach to a hidden view");
        } finally { decoder.release.countDown(); loader.close(); }
    }

    private static void pendingMainDeliveryIsRemovedButOtherHandlersSurvive() throws Exception {
        GateDecoder decoder = new GateDecoder(null);
        IconLoader loader = loader(decoder);
        ImageView visible = new ImageView(null);
        AtomicInteger other = new AtomicInteger();
        try {
            loader.load(visible, "posted");
            // Do not drain the main queue: the bitmap is ready but not yet delivered.
            awaitWithoutMain(() -> cache(loader).size() > 0);
            new Handler(Looper.getMainLooper()).post(other::incrementAndGet);
            loader.releaseUiMemory(); IconLoader.clear(visible);
            Handler.drainMainQueue();
            check(visible.getDrawable() == null, "posted image delivery removed");
            check(other.get() == 1, "only IconLoader's own handler messages are removed");
            assertReleased(loader);
        } finally { loader.close(); }
    }

    private static void cancelledPartialPageNeverPublishes() throws Exception {
        GateDecoder decoder = new GateDecoder("slow");
        IconLoader loader = loader(decoder);
        AtomicInteger ready = new AtomicInteger();
        try {
            ImageView warm = new ImageView(null);
            loader.load(warm, "warm"); await(() -> warm.getDrawable() != null);
            List<AppEntry> page = new ArrayList<>();
            page.add(entry("warm")); page.add(entry("slow"));
            loader.prepare(page, 0, 2, ready::incrementAndGet); decoder.waitUntilBlocked();
            check(((Map<?, ?>) field(loader, "prepared")).size() == 1, "partial page has a pinned bitmap");
            loader.releaseUiMemory(); IconLoader.clear(warm);
            decoder.release.countDown(); await(() -> staleRecycled(loader, decoder));
            assertReleased(loader);
            check(ready.get() == 0, "partially loaded page cannot reveal after stop");
        } finally { decoder.release.countDown(); loader.close(); }
    }

    private static void hiddenLoadsCannotRestartTheQueueEvenAfterInvalidation() throws Exception {
        GateDecoder decoder = new GateDecoder(null);
        IconLoader loader = loader(decoder);
        AtomicInteger ready = new AtomicInteger();
        try {
            loader.releaseUiMemory();
            loader.invalidate(); // E.g. an earlier manual scan finishes while hidden.
            loader.setPaused(true);
            ImageView view = new ImageView(null);
            view.setImageBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888));
            loader.load(view, "late-bind");
            loader.prepare(entries("late-page", 9), 0, 9, ready::incrementAndGet);
            loader.prefetch(entries("late-row", 9), 0, 1);
            loader.prefetchPage(entries("late-tab", 9), 0, 9);
            Handler.drainMainQueue();
            assertReleased(loader);
            check(view.getDrawable() == null, "late hidden bind is cleared instead of queued");
            check(decoder.calls.isEmpty() && ready.get() == 0, "hidden callbacks do not restart decoding");
        } finally { loader.close(); }
    }

    private static void immediateResumeOfSamePathRejectsPreviousGeneration() throws Exception {
        GateDecoder decoder = new GateDecoder("same");
        IconLoader loader = loader(decoder);
        ImageView view = new ImageView(null);
        try {
            loader.load(view, "same"); decoder.waitUntilBlocked();
            loader.releaseUiMemory(); IconLoader.clear(view);
            loader.setPaused(false); loader.load(view, "same");
            decoder.release.countDown(); await(() -> view.getDrawable() != null);
            check(decoder.calls.size() == 2, "same path is decoded in the new lifecycle generation");
            check(decoder.blockedBitmap.isRecycled(), "unpublished old-generation pixels discarded");
            check(view.getDrawable() != decoder.blockedBitmap, "old result never replaces the resumed image");
            check(!((Bitmap) view.getDrawable()).isRecycled(), "resumed image is valid");
        } finally { decoder.release.countDown(); loader.close(); }
    }

    private static void cancelledPostedDeliveryIsSafeEvenIfExecutedLate() throws Exception {
        GateDecoder decoder = new GateDecoder(null);
        IconLoader loader = loader(decoder);
        ImageView view = new ImageView(null);
        try {
            loader.load(view, "posted");
            awaitWithoutMain(() -> cache(loader).size() > 0);
            Object job = ((Map<?, ?>) field(loader, "jobs")).get("posted");
            Bitmap bitmap = cache(loader).get("posted");
            check(job != null && bitmap != null, "delivery is waiting in the handler");
            loader.releaseUiMemory();
            Method deliver = IconLoader.class.getDeclaredMethod("deliver", job.getClass(), Bitmap.class);
            deliver.setAccessible(true); deliver.invoke(loader, job, bitmap);
            check(view.getDrawable() == null, "generation guard rejects a stale delivery even with its original tag");
            assertReleased(loader);
            IconLoader.clear(view);
        } finally { loader.close(); }
    }

    private static void returningPageStillBindsAllVisibleImagesTogether() throws Exception {
        GateDecoder decoder = new GateDecoder(null);
        IconLoader loader = loader(decoder);
        AtomicInteger ready = new AtomicInteger();
        List<ImageView> visible = new ArrayList<>();
        try {
            loader.releaseUiMemory(); loader.setPaused(false);
            loader.prepare(entries("return", 15), 3, 9, () -> {
                for (int i = 3; i < 12; i++) {
                    ImageView view = new ImageView(null); visible.add(view);
                    loader.load(view, "return" + i);
                    check(view.getDrawable() != null, "all resumed visible images bind synchronously");
                }
                ready.incrementAndGet();
            });
            await(() -> ready.get() == 1);
            check(decoder.calls.size() == 9, "return barrier does not wait for offscreen images");
            check(visible.size() == 9, "saved viewport restored as one page");
        } finally { loader.close(); }
    }

    private static void pauseWithoutStopKeepsImagesAndForegroundRequests() throws Exception {
        GateDecoder decoder = new GateDecoder(null);
        IconLoader loader = loader(decoder);
        ImageView view = new ImageView(null);
        AtomicInteger ready = new AtomicInteger();
        try {
            loader.load(view, "keep"); await(() -> view.getDrawable() != null);
            Bitmap bitmap = (Bitmap) view.getDrawable();
            loader.setPaused(true);
            loader.prepare(entries("pending", 1), 0, 1, ready::incrementAndGet);
            check(view.getDrawable() == bitmap && !bitmap.isRecycled(), "soft pause retains the visible page");
            check(cache(loader).size() > 0, "soft pause is not a full release");
            loader.setPaused(false); await(() -> ready.get() == 1);
            check(ready.get() == 1, "pending foreground load survives a temporary pause");
        } finally { loader.close(); }
    }

    private static void runningMemoryWarningDoesNotRefillTheCache() throws Exception {
        GateDecoder decoder = new GateDecoder("visible");
        IconLoader loader = loader(decoder);
        ImageView view = new ImageView(null);
        try {
            loader.load(view, "visible"); decoder.waitUntilBlocked();
            loader.trim(true); decoder.release.countDown();
            await(() -> view.getDrawable() != null);
            check(cache(loader).size() == 0, "pre-warning visible decode does not refill a trimmed cache");
            check(!((Bitmap) view.getDrawable()).isRecycled(), "visible image still renders after a warning");
        } finally { decoder.release.countDown(); loader.close(); }
    }

    private static void repeatedStopResumeDoesNotAccumulateImagesOrJobs() throws Exception {
        GateDecoder decoder = new GateDecoder(null);
        IconLoader loader = loader(decoder);
        ImageView view = new ImageView(null);
        try {
            for (int cycle = 0; cycle < 25; cycle++) {
                loader.setPaused(false); loader.load(view, "repeat");
                await(() -> view.getDrawable() != null);
                check(!((Bitmap) view.getDrawable()).isRecycled(), "valid bitmap after repeated resume");
                loader.releaseUiMemory(); IconLoader.clear(view);
                loader.releaseUiMemory(); // UI_HIDDEN followed by onStop.
                assertReleased(loader);
            }
            check(decoder.calls.size() == 25, "exactly one new decode per lifecycle cycle");
        } finally { loader.close(); }
    }

    private static void closingAReleasedLoaderCannotRestartIt() throws Exception {
        GateDecoder decoder = new GateDecoder(null);
        IconLoader loader = loader(decoder);
        try {
            loader.releaseUiMemory(); loader.close(); loader.setPaused(false);
            ImageView view = new ImageView(null);
            loader.load(view, "after-close"); loader.prepare(entries("closed", 1), 0, 1, () -> {
                throw new AssertionError("closed callback must never run");
            });
            check(decoder.calls.isEmpty(), "closed loader cannot decode again");
            check(((Map<?, ?>) field(loader, "jobs")).isEmpty(), "closed loader cannot queue work");
        } finally { loader.close(); }
    }

    private static void assertReleased(IconLoader loader) throws Exception {
        synchronized (loader) {
            check((Boolean) field(loader, "paused") && (Boolean) field(loader, "uiReleased"), "worker hard-suspended");
            check(!(Boolean) field(loader, "prefetchEnabled"), "prefetch remains disabled");
            check(cache(loader).size() == 0, "RAM cache is empty");
            for (String name : new String[] {"jobs", "prepared"})
                check(((Map<?, ?>) field(loader, name)).isEmpty(), name + " cleared");
            for (String name : new String[] {"queue", "awaiting", "rowPrefetch", "tabPrefetch"})
                check(((Collection<?>) field(loader, name)).isEmpty(), name + " cleared");
            check(field(loader, "preparedCallback") == null, "page callback released");
        }
    }
    private static boolean staleRecycled(IconLoader loader, GateDecoder decoder) {
        synchronized (loader) { return decoder.blockedBitmap != null && decoder.blockedBitmap.isRecycled(); }
    }
    private static final class GateDecoder implements IconLoader.Decoder {
        final String blockedPath;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        volatile Bitmap blockedBitmap;
        GateDecoder(String blockedPath) { this.blockedPath = blockedPath; }
        @Override public Bitmap decode(String path) throws Exception {
            calls.add(path);
            boolean block = path.equals(blockedPath) && entered.getCount() != 0;
            if (block) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("decoder gate timed out");
            }
            Bitmap bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
            if (block) blockedBitmap = bitmap;
            return bitmap;
        }
        void waitUntilBlocked() throws Exception { check(entered.await(5, TimeUnit.SECONDS), "decode is in flight"); }
    }
    private static IconLoader loader(GateDecoder decoder) { return new IconLoader(decoder, 24 * 1024 * 1024); }
    private static AppEntry entry(String path) { return new AppEntry(path, path, "Casual", false, path); }
    private static List<AppEntry> entries(String prefix, int count) {
        List<AppEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) entries.add(entry(prefix + i));
        return entries;
    }
    @SuppressWarnings("unchecked") private static LruCache<String, Bitmap> cache(IconLoader loader) throws Exception {
        return (LruCache<String, Bitmap>) field(loader, "cache");
    }
    private interface Condition { boolean ready() throws Exception; }
    private static void await(Condition condition) throws Exception { await(condition, true); }
    private static void awaitWithoutMain(Condition condition) throws Exception { await(condition, false); }
    private static void await(Condition condition, boolean drain) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (drain) Handler.drainMainQueue();
            if (condition.ready()) return;
            Thread.sleep(1);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for memory-release check");
    }
    private static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
