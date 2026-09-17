package com.ast.ouyalaunch;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

/**
 * Variant 1: Display-name overrides for 6 genres + Favorites.
 * Storage location: /sdcard/Android/data/<package>/genre_names.json
 * - On first run: file is created with default content if it doesn't exist.
 * - On updates: existing file is NOT overwritten.
 * - Users may edit the JSON anytime; values are read on demand.
 *
 * Canonical -> JSON key mapping:
 *   Favorites -> "Favorites"
 *   Casual    -> "Genre1"
 *   Action    -> "Genre2"
 *   Racing    -> "Genre3"
 *   Simulation-> "Genre4"
 *   RPG       -> "Genre5"
 *   Apps      -> "Genre6"
 */
public final class GenreNames {
    private static final String TAG = "GenreNames";
    private static final String FILE_NAME = "genre_names.json";

    // Defaults for display (used when JSON is missing/incomplete)
    private static final String DEF_FAVORITES = "Favorites";
    private static final String DEF_GENRE1 = "Casual";
    private static final String DEF_GENRE2 = "Action";
    private static final String DEF_GENRE3 = "Racing";
    private static final String DEF_GENRE4 = "Simulation";
    private static final String DEF_GENRE5 = "RPG";
    private static final String DEF_GENRE6 = "Apps";

    // In-memory cache of resolved display names
    private static Map<String, String> cache;
    private static long cacheFromLastModified = -1L;

    private GenreNames() {}

    /** Public accessor used by UI to get a display name for a canonical genre. */
    public static synchronized String getDisplayName(Context ctx, String canonical) {
        ensureLoaded(ctx);
        String v = cache.get(canonical);
        if (v == null || v.trim().length() == 0) {
            // last-resort fallback to the canonical text
            return canonical;
        }
        return v;
    }

    /** One filesystem check per refresh, on the shared background storage queue. */
    public static synchronized Map<String, String> getDisplayNames(Context context) {
        ensureLoaded(context);
        Map<String, String> names = new HashMap<>(cache);
        for (Map.Entry<String, String> entry : names.entrySet()) {
            if (entry.getValue() == null || entry.getValue().trim().length() == 0) {
                entry.setValue(entry.getKey());
            }
        }
        return names;
    }

    /** Force a reload next time (e.g., if an options screen changed names). */
    public static synchronized void invalidate() {
        cache = null;
        cacheFromLastModified = -1L;
    }

    // ---------- internal helpers ----------

    private static void ensureLoaded(Context ctx) {
        File jsonFile = resolveTargetFile(ctx);
        ensureDefaultFileExists(jsonFile);
        long lm = jsonFile.lastModified();
        if (cache != null && cacheFromLastModified == lm) {
            return; // cache valid
        }
        loadFrom(jsonFile);
        cacheFromLastModified = jsonFile.lastModified();
    }

    /** Returns /sdcard/Android/data/<pkg>/genre_names.json (tries to create the directory if needed). */
    private static File resolveTargetFile(Context ctx) {
        File extRoot = Environment.getExternalStorageDirectory(); // /sdcard
        String pkg = ctx.getPackageName();
        File targetDir = new File(extRoot, "Android/data/" + pkg);
        if (!targetDir.exists()) {
            // Try to create; ignore result (may fail without permission)
            targetDir.mkdirs();
        }
        return new File(targetDir, FILE_NAME);
    }

    /** Creates the default JSON only if file doesn't already exist. Never overwrites.
     *  IMPORTANT: We write JSON text manually to guarantee key order:
     *  Favorites, Genre1, Genre2, Genre3, Genre4, Genre5, Genre6.
     */
    private static void ensureDefaultFileExists(File f) {
        if (f.exists()) return;
        String defaultJson =
                "{\n" +
                "  \"Favorites\": \"" + DEF_FAVORITES + "\",\n" +
                "  \"Genre1\": \"" + DEF_GENRE1 + "\",\n" +
                "  \"Genre2\": \"" + DEF_GENRE2 + "\",\n" +
                "  \"Genre3\": \"" + DEF_GENRE3 + "\",\n" +
                "  \"Genre4\": \"" + DEF_GENRE4 + "\",\n" +
                "  \"Genre5\": \"" + DEF_GENRE5 + "\",\n" +
                "  \"Genre6\": \"" + DEF_GENRE6 + "\"\n" +
                "}\n";
        writeTextSafely(f, defaultJson);
    }

    /** Loads JSON from file into the cache map with safe fallbacks. */
    private static void loadFrom(File f) {
        Map<String, String> m = new HashMap<String, String>(8);
        // Set hard defaults first
        m.put("Favorites", DEF_FAVORITES);
        m.put("Casual", DEF_GENRE1);
        m.put("Action", DEF_GENRE2);
        m.put("Racing", DEF_GENRE3);
        m.put("Simulation", DEF_GENRE4);
        m.put("RPG", DEF_GENRE5);
        m.put("Apps", DEF_GENRE6);

        if (!f.exists() || !f.canRead()) {
            cache = m;
            return;
        }
        String txt = readTextSafely(f);
        if (txt == null) {
            cache = m;
            return;
        }
        try {
            JSONObject obj = new JSONObject(txt);
            // Map JSON keys to canonical names
            m.put("Favorites", obj.optString("Favorites", DEF_FAVORITES));
            m.put("Casual", obj.optString("Genre1", DEF_GENRE1));
            m.put("Action", obj.optString("Genre2", DEF_GENRE2));
            m.put("Racing", obj.optString("Genre3", DEF_GENRE3));
            m.put("Simulation", obj.optString("Genre4", DEF_GENRE4));
            m.put("RPG", obj.optString("Genre5", DEF_GENRE5));
            m.put("Apps", obj.optString("Genre6", DEF_GENRE6));
        } catch (JSONException e) {
            Log.w(TAG, "Invalid JSON in " + f.getAbsolutePath() + ", using defaults.", e);
        }
        cache = m;
    }

    private static void writeTextSafely(File f, String content) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, false), Charset.forName("UTF-8")));
            try {
                bw.write(content);
                bw.flush();
            } finally {
                bw.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to write default " + f.getAbsolutePath(), e);
        }
    }

    private static String readTextSafely(File f) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), Charset.forName("UTF-8")));
            try {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n'); // FIX: correct Java char literal
                }
                return sb.toString();
            } finally {
                br.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to read " + f.getAbsolutePath(), e);
            return null;
        }
    }
}
