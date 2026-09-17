package com.ast.ouyalaunch;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.util.Log;
import androidx.core.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Manual incremental scan. Missing/USB-unavailable packages keep their existing entries. */
public final class AppScanner {
    private static final String TAG = "AppScanner";

    public static List<AppEntry> buildCache(Context context, List<AppEntry> existing) throws IOException {
        PackageManager pm = context.getPackageManager();
        Map<String, AppEntry> merged = new LinkedHashMap<>();
        for (AppEntry entry : existing) merged.put(entry.packageName, new AppEntry(entry));
        TreeSet<String> packages = new TreeSet<>();
        collect(pm, Intent.CATEGORY_LAUNCHER, packages);
        collect(pm, "tv.ouya.intent.category.GAME", packages);
        File directory = new File(context.getFilesDir(), "icons");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create icon cache");
        int reused = 0, updated = 0;
        for (String pkg : packages) {
            Bitmap bitmap = null;
            try {
                PackageInfo info = pm.getPackageInfo(pkg, 0);
                ApplicationInfo app = info.applicationInfo;
                if (app == null || app.sourceDir == null) continue;
                long length = new File(app.sourceDir).length();
                AppEntry previous = merged.get(pkg);
                File target = new File(directory, pkg + ".png");
                if (previous != null && previous.packageUpdatedAt == info.lastUpdateTime
                        && previous.apkLength == length
                        && previous.iconCacheVersion == BitmapTools.CACHE_VERSION
                        && target.getAbsolutePath().equals(previous.iconPath)
                        && target.isFile() && target.length() > 0
                        && !new File(target.getPath() + ".bak").exists()) {
                    reused++;
                    continue;
                }
                bitmap = decodeOuyaIcon(context, app, pkg);
                if (bitmap == null) continue;
                writeBitmap(target, bitmap);
                CharSequence label = pm.getApplicationLabel(app);
                AppEntry entry = previous == null ? new AppEntry() : new AppEntry(previous);
                entry.packageName = pkg;
                entry.title = label == null ? pkg : label.toString();
                if (entry.genre == null || entry.genre.length() == 0) entry.genre = "Casual";
                entry.iconPath = target.getAbsolutePath();
                entry.packageUpdatedAt = info.lastUpdateTime;
                entry.apkLength = length;
                entry.iconCacheVersion = BitmapTools.CACHE_VERSION;
                merged.put(pkg, entry);
                updated++;
            } catch (Exception error) {
                Log.w(TAG, "Keeping previous entry after scan failure: " + pkg, error);
            } catch (OutOfMemoryError error) {
                Log.w(TAG, "Not enough memory to decode " + pkg + "; keeping previous entry");
            } finally {
                if (bitmap != null) bitmap.recycle(); // Scanner images are never displayed directly.
            }
        }
        List<AppEntry> result = new ArrayList<>(merged.values());
        final Map<String, String> sortKeys = new HashMap<>();
        for (AppEntry entry : result) sortKeys.put(entry.packageName,
                (entry.title == null ? "" : entry.title).toLowerCase(Locale.ROOT));
        Collections.sort(result, (a, b) -> sortKeys.get(a.packageName).compareTo(sortKeys.get(b.packageName)));
        Log.i(TAG, "Scan: " + result.size() + " entries, " + reused + " icons reused, " + updated + " updated");
        return result;
    }

    private static void collect(PackageManager pm, String category, TreeSet<String> packages) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> entries = pm.queryIntentActivities(intent, 0);
        if (entries == null) return;
        for (ResolveInfo entry : entries) {
            if (entry != null && entry.activityInfo != null && entry.activityInfo.packageName != null) {
                packages.add(entry.activityInfo.packageName);
            }
        }
    }

    private static Bitmap decodeOuyaIcon(Context context, ApplicationInfo app, String pkg) {
        // Zip and both image streams are closed on every path, including "no icon".
        try (ZipFile zip = new ZipFile(app.sourceDir)) {
            List<String> candidates = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String lower = entry.getName().toLowerCase(Locale.ROOT);
                String basename = lower.substring(lower.lastIndexOf('/') + 1);
                if (basename.equals("ouya_icon.png") || basename.equals("ouya_icon.webp")
                        || basename.equals("ouya_icon.jpg") || basename.equals("ouya_icon.jpeg")) {
                    candidates.add(entry.getName());
                }
            }
            // Prefer the original artwork; decoding always downsamples to the actual display size.
            Collections.sort(candidates, (a, b) -> densityScore(b) - densityScore(a));
            for (String candidate : candidates) {
                final ZipEntry entry = zip.getEntry(candidate);
                try {
                    Bitmap bitmap = BitmapTools.decode(() -> zip.getInputStream(entry));
                    if (bitmap != null) return bitmap;
                } catch (IOException | RuntimeException error) {
                    Log.w(TAG, "Invalid icon candidate " + candidate, error);
                }
            }
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Cannot read APK icon for " + pkg, error);
        }
        try {
            Resources resources = context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY).getResources();
            int id = resources.getIdentifier("ouya_icon", "drawable", pkg);
            if (id == 0) id = resources.getIdentifier("ouya_icon", "mipmap", pkg);
            if (id != 0) {
                final int resourceId = id;
                // Raw resource bytes avoid density-dependent bitmap enlargement.
                return BitmapTools.decode(() -> resources.openRawResource(resourceId));
            }
        } catch (Exception error) {
            Log.w(TAG, "Cannot read resource icon for " + pkg, error);
        }
        return null;
    }

    private static void writeBitmap(File target, Bitmap bitmap) throws IOException {
        AtomicFile file = new AtomicFile(target);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("PNG encoding failed");
            file.finishWrite(stream);
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            if (stream != null) file.failWrite(stream);
            throw error;
        }
    }

    private static int densityScore(String name) {
        String path = name.toLowerCase(Locale.ROOT);
        if (path.contains("xxxhdpi")) return 5;
        if (path.contains("xxhdpi")) return 4;
        if (path.contains("xhdpi")) return 3;
        if (path.contains("hdpi")) return 2;
        if (path.contains("mdpi")) return 1;
        return 0;
    }
    private AppScanner() { }
}
