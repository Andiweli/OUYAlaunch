package com.ast.ouyalaunch;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Scans launchable apps and builds a cache of OUYA-compatible entries, identified by
 * the presence of an OUYA tile icon. The icon is accepted either as an APK file
 * named ouya_icon.* or as an Android drawable/mipmap resource named ouya_icon.
 *
 * NOTE: Keep Java 7 compatible (no lambdas).
 */
public class AppScanner {

    private static final String TAG = "AppScanner";
    private static final String ICON_FILE_NAME = "ouya_icon.png";

    /** Build the full list of OUYA-compatible apps (has ouya_icon.png). */
    public static List<AppEntry> buildCache(Context ctx) {
        List<AppEntry> out = new ArrayList<AppEntry>();
        PackageManager pm = ctx.getPackageManager();

        try {
            // 1) Get all launchable activities. OUYA games sometimes expose only
            // the OUYA GAME category, while normal Android apps use LAUNCHER.
            List<ResolveInfo> infos = new ArrayList<ResolveInfo>();
            Intent intent = new Intent(Intent.ACTION_MAIN, null);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> launcherInfos = pm.queryIntentActivities(intent, 0);
            if (launcherInfos != null) infos.addAll(launcherInfos);

            Intent ouyaIntent = new Intent(Intent.ACTION_MAIN, null);
            ouyaIntent.addCategory("tv.ouya.intent.category.GAME");
            List<ResolveInfo> ouyaInfos = pm.queryIntentActivities(ouyaIntent, 0);
            if (ouyaInfos != null) infos.addAll(ouyaInfos);

            // 2) Collect unique package names
            Set<String> packages = new HashSet<String>();
            for (int i = 0; i < infos.size(); i++) {
                ResolveInfo ri = infos.get(i);
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg != null && pkg.length() > 0) {
                    packages.add(pkg);
                }
            }

            // 3) Prepare icon cache dir
            File iconDir = new File(ctx.getFilesDir(), "icons");
            if (!iconDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                iconDir.mkdirs();
            }

            // 4) Iterate packages, look for ouya_icon.png/resource, and cache.
            for (String pkg : packages) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if (ai == null || ai.sourceDir == null) continue;

                    Bitmap bmp = decodeOuyaIcon(ctx, pkg, ai);
                    if (bmp == null) {
                        // No OUYA icon → not considered OUYA-compatible
                        continue;
                    }

                    File outPng = new File(iconDir, pkg + ".png");
                    FileOutputStream fos = new FileOutputStream(outPng);
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.flush();
                    fos.close();

                    // Build entry
                    CharSequence label = pm.getApplicationLabel(ai);
                    String title = label != null ? label.toString() : pkg;

                    AppEntry e = new AppEntry();
                    e.packageName = pkg;
                    e.title = title;
                    e.genre = "Casual";     // default; user can reassign, DataStore preserves
                    e.favorite = false;
                    e.iconPath = outPng.getAbsolutePath();

                    out.add(e);

                    Log.d(TAG, "Added OUYA app: " + pkg + " (" + title + "), icon cached");
                } catch (Throwable perApp) {
                    Log.w(TAG, "Failed scanning " + pkg + ": " + perApp.getMessage());
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Fehler beim App-Scan: " + t.getMessage(), t);
        }

        Log.i(TAG, "Scan abgeschlossen, " + out.size() + " OUYA-kompatible Apps gefunden.");
        // Sort A–Z by title
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                String ta = (a != null && a.title != null) ? a.title : "";
                String tb = (b != null && b.title != null) ? b.title : "";
                return ta.toLowerCase(Locale.US).compareTo(tb.toLowerCase(Locale.US));
            }
        });
        return out;
    }


    /** Decode ouya_icon either from the packaged APK file or from Android resources. */
    private static Bitmap decodeOuyaIcon(Context ctx, String pkg, ApplicationInfo ai) {
        Bitmap bmp = null;

        if (ai != null && ai.sourceDir != null) {
            IconMatch match = findOuyaIconInApk(ai.sourceDir);
            if (match != null) {
                InputStream is = null;
                try {
                    is = match.open();
                    if (is != null) bmp = BitmapFactory.decodeStream(is);
                } catch (Throwable t) {
                    Log.w(TAG, "APK ouya_icon decode failed for " + pkg + ": " + t.getMessage());
                } finally {
                    try { if (is != null) is.close(); } catch (Throwable ignore) {}
                }
                if (bmp != null) return bmp;
            }
        }

        try {
            Context appCtx = ctx.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY);
            Resources res = appCtx.getResources();
            int id = res.getIdentifier("ouya_icon", "drawable", pkg);
            if (id == 0) id = res.getIdentifier("ouya_icon", "mipmap", pkg);
            if (id != 0) {
                bmp = BitmapFactory.decodeResource(res, id);
                if (bmp != null) return bmp;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Resource ouya_icon lookup failed for " + pkg + ": " + t.getMessage());
        }

        return null;
    }

    /** Represents a lazy-open match inside the APK. */
    private static class IconMatch {
        final String apkPath;
        final String entryName;
        IconMatch(String apkPath, String entryName) { this.apkPath = apkPath; this.entryName = entryName; }
        InputStream open() {
            try {
                ZipFile zf = new ZipFile(apkPath);
                ZipEntry ze = zf.getEntry(entryName);
                if (ze == null) return null;
                // NOTE: The caller decodes & closes the stream; we keep ZipFile referenced
                // until stream is closed by framework.
                return zf.getInputStream(ze);
            } catch (Throwable t) {
                Log.w(TAG, "open() failed for " + entryName + " in " + apkPath + ": " + t.getMessage());
                return null;
            }
        }
    }

    /**
     * Find the best-matching "ouya_icon.png" entry within the given APK zip path.
     * Preference order: xxxhdpi > xxhdpi > xhdpi > hdpi > mdpi > any.
     */
    private static IconMatch findOuyaIconInApk(String apkPath) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(apkPath);
            // Collect candidates
            List<String> candidates = new ArrayList<String>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze == null) continue;
                String name = ze.getName();
                if (name == null) continue;
                String lower = name.toLowerCase(Locale.US);
                if (lower.endsWith("/" + ICON_FILE_NAME) || lower.equals(ICON_FILE_NAME)
                        || lower.endsWith("/ouya_icon.webp") || lower.endsWith("/ouya_icon.jpg")
                        || lower.endsWith("/ouya_icon.jpeg")) {
                    candidates.add(name);
                }
            }
            if (candidates.isEmpty()) return null;

            // Rank by density hints if available
            int bestScore = -1;
            String best = null;
            for (int i = 0; i < candidates.size(); i++) {
                String n = candidates.get(i);
                String ln = n.toLowerCase(Locale.US);
                int score = densityScore(ln);
                if (score > bestScore) {
                    bestScore = score;
                    best = n;
                }
            }
            if (best == null) best = candidates.get(0);
            // Don't keep the ZipFile open; IconMatch#open will reopen on demand
            zf.close();
            return new IconMatch(apkPath, best);
        } catch (Throwable t) {
            try { if (zf != null) zf.close(); } catch (Throwable ignore) {}
            return null;
        }
    }

    private static int densityScore(String pathLower) {
        // Higher score = higher preference
        if (pathLower.contains("xxxhdpi")) return 5;
        if (pathLower.contains("xxhdpi"))  return 4;
        if (pathLower.contains("xhdpi"))   return 3;
        if (pathLower.contains("hdpi"))    return 2;
        if (pathLower.contains("mdpi"))    return 1;
        return 0;
    }
}
