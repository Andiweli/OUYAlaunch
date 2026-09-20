package com.ast.ouyalaunch;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One decoder: visible work first, then nearby rows, then one bounded next-tab window. */
final class IconLoader {
    private static final int MAX_PREFETCH = 9;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final Map<String, Job> jobs = new HashMap<>();
    private final LruCache<String, Bitmap> cache;
    private final int budget;
    private final Decoder decoder;
    private boolean paused;
    // Hard suspension after the UI is hidden: even late binds must not queue new work.
    private boolean uiReleased;
    private boolean closed;
    private boolean prefetchEnabled = true;
    private int generation;
    private int memoryGeneration;
    // These sets contain paths only. Both prefetch scopes share the existing LRU.
    private final Set<String> rowPrefetch = new LinkedHashSet<>();
    private final Set<String> tabPrefetch = new LinkedHashSet<>();
    // Only the incoming visible page is pinned until it has been bound.
    private final Map<String, Bitmap> prepared = new HashMap<>();
    private final Set<String> awaiting = new java.util.HashSet<>();
    private Runnable preparedCallback;

    interface Decoder { Bitmap decode(String path) throws Exception; }

    private static final class Loaded {
        final String path;
        Loaded(String path) { this.path = path; }
    }

    private static final class Job {
        final String path;
        final int generation;
        final int memoryGeneration;
        final List<WeakReference<ImageView>> targets = new ArrayList<>();
        boolean prefetch;
        Job(String path, int generation, int memoryGeneration) {
            this.path = path;
            this.generation = generation;
            this.memoryGeneration = memoryGeneration;
        }
        boolean hasVisibleTarget() {
            for (WeakReference<ImageView> ref : targets) {
                ImageView view = ref.get();
                if (view != null && view.getTag() == this) return true;
            }
            return false;
        }
    }

    IconLoader() {
        this(path -> BitmapTools.decodeCachedPreview(() -> new FileInputStream(path)),
                (int) Math.max(2L * 1024 * 1024,
                        Math.min(24L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)));
    }

    /** Package-private injection keeps queue/cancellation tests deterministic. */
    IconLoader(Decoder decoder, int memoryBudget) {
        if (decoder == null || memoryBudget <= 0) throw new IllegalArgumentException("Invalid decoder/budget");
        this.decoder = decoder;
        budget = memoryBudget;
        cache = new LruCache<String, Bitmap>(budget) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight();
            }
        };
        Thread worker = new Thread(this::work, "OUYAlaunch-icons");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    synchronized void load(ImageView view, String path) {
        if (closed || uiReleased || path == null) { clear(view); return; }
        Object tag = view.getTag();
        if (tag instanceof Loaded && path.equals(((Loaded) tag).path) && view.getDrawable() != null) return;
        Bitmap bitmap = prepared.get(path);
        if (bitmap == null) bitmap = cache.get(path);
        if (bitmap != null) {
            view.setTag(new Loaded(path));
            view.setImageBitmap(bitmap);
            return;
        }
        Job job = jobs.get(path);
        if (job != null && view.getTag() == job) return;
        clear(view);
        if (job == null) {
            job = new Job(path, generation, memoryGeneration);
            jobs.put(path, job);
            queue.addFirst(job);
        } else if (queue.remove(job)) {
            queue.addFirst(job);
        }
        job.prefetch = false;
        job.targets.add(new WeakReference<>(view));
        view.setTag(job);
        pruneQueue();
        notifyAll();
    }

    synchronized void prefetch(List<AppEntry> entries, int first, int direction) {
        if (closed || paused || !prefetchEnabled || (direction != 1 && direction != -1)) return;
        rowPrefetch.clear();
        for (int count = 0, i = first; count < MAX_PREFETCH && i >= 0 && i < entries.size();
             count++, i += direction) {
            addPrefetch(rowPrefetch, entries.get(i).iconPath);
        }
        orderPrefetch();
    }

    /** Best-effort warmup; never adds a page callback or pins another page of bitmaps. */
    synchronized void prefetchPage(List<AppEntry> entries, int first, int count) {
        if (closed || paused || !prefetchEnabled) return;
        tabPrefetch.clear();
        // At most half the LRU for one predicted page; no increased heap/cache budget.
        int limit = Math.min(MAX_PREFETCH, budget / 2 / (BitmapTools.WIDTH * BitmapTools.HEIGHT * 4));
        int start = Math.max(0, first);
        int end = (int) Math.min(entries.size(), (long) start + Math.max(0, Math.min(count, limit)));
        for (int i = start; i < end; i++) addPrefetch(tabPrefetch, entries.get(i).iconPath);
        orderPrefetch();
    }

    private void addPrefetch(Set<String> paths, String path) {
        if (path == null) return;
        paths.add(path);
        if (prepared.containsKey(path) || cache.get(path) != null || jobs.containsKey(path)) return;
        Job job = new Job(path, generation, memoryGeneration);
        job.prefetch = true;
        jobs.put(path, job);
        queue.addLast(job);
    }

    /** Called on the UI thread: only unclaimed speculative jobs are reordered. */
    private void orderPrefetch() {
        pruneQueue();
        Map<String, Job> speculative = new LinkedHashMap<>();
        for (Iterator<Job> it = queue.iterator(); it.hasNext();) {
            Job job = it.next();
            if (!awaiting.contains(job.path) && !job.hasVisibleTarget()) {
                job.prefetch = true;
                speculative.put(job.path, job);
                it.remove();
            }
        }
        appendPrefetch(rowPrefetch, speculative);
        appendPrefetch(tabPrefetch, speculative);
        notifyAll();
    }

    private void appendPrefetch(Set<String> paths, Map<String, Job> speculative) {
        for (String path : paths) {
            Job job = speculative.remove(path);
            if (job != null) queue.addLast(job);
        }
    }

    synchronized void cancelPrefetch() {
        rowPrefetch.clear();
        tabPrefetch.clear();
        // A promoted/visible job has prefetch=false. An in-flight decode can finish,
        // but no obsolete row/tab request is left waiting ahead of a new page.
        for (Iterator<Job> it = queue.iterator(); it.hasNext();) {
            Job job = it.next();
            if (job.prefetch) {
                it.remove();
                if (jobs.get(job.path) == job) jobs.remove(job.path);
            }
        }
    }

    /** Prepare exactly the upcoming viewport before MainActivity replaces the page. */
    synchronized void prepare(List<AppEntry> entries, int first, int count, Runnable ready) {
        if (closed || uiReleased) return;
        cancelPreparation();
        int start = Math.max(0, first);
        int end = (int) Math.min(entries.size(), (long) start + Math.max(0, count));
        for (int i = start; i < end; i++) {
            String path = entries.get(i).iconPath;
            if (path == null) continue;
            Bitmap bitmap = cache.get(path);
            if (bitmap != null) { prepared.put(path, bitmap); continue; }
            awaiting.add(path);
            Job job = jobs.get(path);
            if (job == null) {
                job = new Job(path, generation, memoryGeneration);
                jobs.put(path, job);
                queue.addFirst(job);
            } else if (queue.remove(job)) queue.addFirst(job);
            job.prefetch = false;
        }
        // Promote matching predictions first, discard the remaining speculation second.
        cancelPrefetch();
        preparedCallback = ready;
        notifyAll();
        finishPreparationIfReady();
    }

    synchronized void cancelPreparation() {
        preparedCallback = null;
        awaiting.clear();
        prepared.clear();
        pruneQueue();
    }

    private void finishPreparationIfReady() {
        if (awaiting.isEmpty() && preparedCallback != null) {
            Runnable callback = preparedCallback;
            preparedCallback = null;
            callback.run(); // prepare/deliver are called only on the main thread.
        }
    }

    private void pruneQueue() {
        for (Iterator<Job> it = queue.iterator(); it.hasNext();) {
            Job job = it.next();
            if (!awaiting.contains(job.path) && !rowPrefetch.contains(job.path)
                    && !tabPrefetch.contains(job.path) && !job.hasVisibleTarget()) {
                it.remove();
                if (jobs.get(job.path) == job) jobs.remove(job.path);
            }
        }
    }

    static void clear(ImageView view) {
        view.setTag(null);
        view.setImageDrawable(null);
    }

    synchronized void invalidate() {
        generation++;
        memoryGeneration++;
        prefetchEnabled = !uiReleased;
        preparedCallback = null;
        awaiting.clear();
        prepared.clear();
        rowPrefetch.clear();
        tabPrefetch.clear();
        queue.clear();
        jobs.clear();
        cache.evictAll();
        main.removeCallbacksAndMessages(null);
    }

    synchronized void setPaused(boolean value) {
        paused = value;
        if (value) cancelPrefetch();
        else { uiReleased = false; prefetchEnabled = true; }
        // Pausing is not invalidation: keep completed bitmaps and visible pending work.
        // A decoder already in progress may finish once; the worker then waits.
        notifyAll();
    }

    /**
     * Stop all image work and drop every loader-owned bitmap reference. Invalidation
     * is atomic with the pause: an in-flight decode cannot refill the cache, publish
     * to a hidden View or complete an obsolete page callback after this returns.
     * AppAdapter must also clear its ImageViews; published/shared bitmaps are NOT recycled.
     */
    synchronized void releaseUiMemory() {
        paused = true;
        uiReleased = true;
        invalidate();
        notifyAll();
    }

    synchronized void trim(boolean severe) {
        memoryGeneration++;
        prefetchEnabled = false; // Do not immediately refill after an OS memory warning.
        cancelPrefetch();
        if (severe) {
            cache.evictAll();
            // Do not discard the page currently being assembled under its loading overlay.
            if (preparedCallback == null) prepared.clear();
        }
        else for (String key : cache.snapshot().keySet()) {
            if (cache.size() <= budget / 2) break;
            cache.remove(key);
        }
    }

    synchronized void close() {
        closed = true;
        invalidate();
        notifyAll();
    }

    private void work() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        while (true) {
            final Job job;
            synchronized (this) {
                while (!closed && (paused || queue.isEmpty())) {
                    try { wait(); } catch (InterruptedException ignored) { }
                }
                if (closed) return;
                job = queue.removeFirst();
            }
            decodeAndPost(job);
        }
    }

    // Keep decoded pixels out of the long-lived worker's waiting stack frame.
    private void decodeAndPost(Job job) {
        Bitmap bitmap = null;
        try {
            bitmap = decoder.decode(job.path);
        } catch (Exception error) {
            Log.w("IconLoader", "Cannot load " + job.path, error);
        } catch (OutOfMemoryError error) {
            trim(true);
            Log.w("IconLoader", "Icon skipped because memory is low");
        }
        synchronized (this) {
            if (closed || uiReleased || job.generation != generation
                    || (job.prefetch && job.memoryGeneration != memoryGeneration)) {
                if (jobs.get(job.path) == job) jobs.remove(job.path);
                if (bitmap != null) bitmap.recycle(); // Never published to a View/cache.
                return;
            }
            // A visible request can still finish during a memory warning, but must
            // not repopulate the trimmed cache with a pre-warning result.
            if (bitmap != null && job.memoryGeneration == memoryGeneration) cache.put(job.path, bitmap);
            final Bitmap ready = bitmap;
            main.post(() -> deliver(job, ready));
        }
    }

    private synchronized void deliver(Job job, Bitmap bitmap) {
        if (closed || uiReleased || job.generation != generation) return;
        if (jobs.get(job.path) == job) jobs.remove(job.path);
        for (WeakReference<ImageView> ref : job.targets) {
            ImageView view = ref.get();
            if (view != null && view.getTag() == job) {
                if (bitmap == null) clear(view);
                else {
                    view.setTag(new Loaded(job.path));
                    view.setImageBitmap(bitmap);
                }
            }
        }
        if (awaiting.remove(job.path)) {
            if (bitmap != null) prepared.put(job.path, bitmap);
            finishPreparationIfReady(); // Failed/missing icons must not block a page forever.
        }
    }
}
