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
import java.util.List;
import java.util.Map;

/** One low-priority decoder; visible requests precede at most nine prefetched icons. */
final class IconLoader {
    private static final int MAX_PREFETCH = 9;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final Map<String, Job> jobs = new HashMap<>();
    private final LruCache<String, Bitmap> cache;
    private final int budget;
    private boolean paused;
    private boolean closed;
    private int generation;
    // Only the upcoming viewport is pinned, not the whole app library.
    private final Map<String, Bitmap> prepared = new HashMap<>();
    private final java.util.Set<String> awaiting = new java.util.HashSet<>();
    private Runnable preparedCallback;

    private static final class Loaded {
        final String path;
        Loaded(String path) { this.path = path; }
    }

    private static final class Job {
        final String path;
        final int generation;
        final List<WeakReference<ImageView>> targets = new ArrayList<>();
        boolean prefetch;
        Job(String path, int generation) { this.path = path; this.generation = generation; }
        boolean hasVisibleTarget() {
            for (WeakReference<ImageView> ref : targets) {
                ImageView view = ref.get();
                if (view != null && view.getTag() == this) return true;
            }
            return false;
        }
    }

    IconLoader() {
        budget = (int) Math.max(2L * 1024 * 1024,
                Math.min(24L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8));
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
        if (closed || path == null) { clear(view); return; }
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
            job = new Job(path, generation);
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
        if (closed || paused) return;
        // Drop obsolete speculative work when the viewport/direction changes.
        for (Iterator<Job> it = queue.iterator(); it.hasNext();) {
            Job job = it.next();
            if (job.prefetch && !awaiting.contains(job.path) && !job.hasVisibleTarget()) { it.remove(); jobs.remove(job.path); }
        }
        for (int count = 0, i = first; count < MAX_PREFETCH && i >= 0 && i < entries.size();
             count++, i += direction) {
            String path = entries.get(i).iconPath;
            if (path == null || cache.get(path) != null || jobs.containsKey(path)) continue;
            Job job = new Job(path, generation);
            job.prefetch = true;
            jobs.put(path, job);
            queue.addLast(job);
        }
        pruneQueue();
        notifyAll();
    }

    /** Prepare the next viewport before MainActivity replaces the displayed page. */
    synchronized void prepare(List<AppEntry> entries, int first, int count, Runnable ready) {
        if (closed) return;
        cancelPreparation();
        int end = Math.min(entries.size(), first + count);
        for (int i = Math.max(0, first); i < end; i++) {
            String path = entries.get(i).iconPath;
            if (path == null) continue;
            Bitmap bitmap = cache.get(path);
            if (bitmap != null) { prepared.put(path, bitmap); continue; }
            awaiting.add(path);
            Job job = jobs.get(path);
            if (job == null) {
                job = new Job(path, generation);
                jobs.put(path, job);
                queue.addFirst(job);
            } else if (queue.remove(job)) queue.addFirst(job);
            job.prefetch = false;
        }
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
            if (!job.prefetch && !awaiting.contains(job.path) && !job.hasVisibleTarget()) { it.remove(); jobs.remove(job.path); }
        }
    }

    static void clear(ImageView view) {
        view.setTag(null);
        view.setImageBitmap(null);
    }

    synchronized void invalidate() {
        generation++;
        preparedCallback = null;
        awaiting.clear();
        prepared.clear();
        queue.clear();
        jobs.clear();
        cache.evictAll();
        main.removeCallbacksAndMessages(null);
    }

    synchronized void setPaused(boolean value) {
        paused = value;
        // Pausing is not invalidation: keep completed bitmaps and pending work.
        // A decoder already in progress may finish once; the worker then waits.
        notifyAll();
    }

    synchronized void trim(boolean severe) {
        if (severe) {
            cache.evictAll();
            // Do not discard the page currently being assembled underneath its loading overlay.
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
            Bitmap bitmap = null;
            try {
                bitmap = BitmapTools.decode(() -> new FileInputStream(job.path));
            } catch (Exception error) {
                Log.w("IconLoader", "Cannot load " + job.path, error);
            } catch (OutOfMemoryError error) {
                trim(true);
                Log.w("IconLoader", "Icon skipped because memory is low");
            }
            synchronized (this) {
                if (closed || job.generation != generation) {
                    if (bitmap != null) bitmap.recycle(); // never published to a View/cache
                    continue;
                }
                if (bitmap != null) cache.put(job.path, bitmap);
                final Bitmap ready = bitmap;
                main.post(() -> deliver(job, ready));
            }
        }
    }

    private synchronized void deliver(Job job, Bitmap bitmap) {
        if (closed || job.generation != generation) return;
        if (jobs.get(job.path) == job) jobs.remove(job.path);
        for (WeakReference<ImageView> ref : job.targets) {
            ImageView view = ref.get();
            if (view != null && view.getTag() == job) {
                view.setTag(bitmap == null ? null : new Loaded(job.path));
                view.setImageBitmap(bitmap);
            }
        }
        if (awaiting.remove(job.path)) {
            if (bitmap != null) prepared.put(job.path, bitmap);
            finishPreparationIfReady(); // Failed/missing icons must not block a page forever.
        }
    }
}
