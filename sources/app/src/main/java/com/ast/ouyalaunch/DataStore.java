package com.ast.ouyalaunch;

import android.content.Context;
import android.util.Log;
import androidx.core.util.AtomicFile;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** All load/scan/save jobs share one process-wide queue, including Activity recreation. */
public final class DataStore {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "OUYAlaunch-storage");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final File directory;
    private final Gson gson = new Gson();

    public DataStore(Context context) { directory = context.getApplicationContext().getFilesDir(); }
    public static void execute(Runnable task) { IO.execute(task); }

    public static List<AppEntry> snapshot(List<AppEntry> entries) {
        List<AppEntry> copy = new ArrayList<>(entries.size());
        for (AppEntry entry : entries) copy.add(new AppEntry(entry));
        return copy;
    }

    /** Null means no database yet; a valid empty list remains an intentional empty list. */
    public List<AppEntry> load() throws IOException {
        File file = new File(directory, "apps.json");
        if (!file.exists() && !new File(directory, "apps.json.bak").exists()) return null;
        try {
            String json = new String(new AtomicFile(file).readFully(), "UTF-8");
            Type type = new TypeToken<ArrayList<AppEntry>>() { }.getType();
            List<AppEntry> result = gson.fromJson(json, type);
            if (result == null) throw new IOException("Empty app database");
            java.util.HashSet<String> packages = new java.util.HashSet<>();
            for (AppEntry entry : result) {
                if (entry == null || entry.packageName == null || entry.packageName.length() == 0
                        || !packages.add(entry.packageName)) throw new IOException("Invalid app database");
            }
            return result;
        } catch (RuntimeException error) {
            throw new IOException("Cannot read app database", error);
        }
    }

    /** Must run on the storage queue. AtomicFile keeps the previous version on failure. */
    public void save(List<AppEntry> entries) throws IOException {
        AtomicFile file = new AtomicFile(new File(directory, "apps.json"));
        FileOutputStream stream = null;
        try {
            byte[] bytes = gson.toJson(entries).getBytes("UTF-8");
            stream = file.startWrite();
            stream.write(bytes);
            file.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            if (stream != null) file.failWrite(stream);
            Log.e("DataStore", "App database was not saved", error);
            throw error;
        }
    }
}
