package com.ast.ouyalaunch;

import android.content.Context;
import android.util.Log;
import androidx.core.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;

/** Display preferences only. AppEntry.genre always remains the original stable category key. */
final class CategorySettings {
    static final int MAX_NAME_LENGTH = 24;
    private final String[] names;
    private final boolean[] enabled;

    CategorySettings(String[] labels, boolean[] visible) {
        if (labels.length != MainActivity.GENRES.size() || visible.length != labels.length)
            throw new IllegalArgumentException("Invalid category count");
        names = labels.clone();
        enabled = visible.clone();
        boolean any = false;
        for (int i = 0; i < names.length; i++) {
            String name = names[i] == null ? "" : names[i].trim();
            if (name.isEmpty()) name = MainActivity.GENRES.get(i);
            if (name.length() > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH);
            names[i] = name;
            any |= enabled[i];
        }
        if (!any) throw new IllegalArgumentException("At least one category must stay visible");
    }

    static CategorySettings defaults() {
        boolean[] visible = new boolean[MainActivity.GENRES.size()];
        java.util.Arrays.fill(visible, true);
        return new CategorySettings(MainActivity.GENRES.toArray(new String[0]), visible);
    }
    String name(int index) { return names[index]; }
    boolean enabled(int index) { return enabled[index]; }
    int firstVisible() {
        for (int i = 0; i < enabled.length; i++) if (enabled[i]) return i;
        return 0;
    }
    int neighbor(int index, int direction) {
        for (int i = index + direction; i >= 0 && i < enabled.length; i += direction) {
            if (enabled[i]) return i;
        }
        return index;
    }
    int nextAssignable(int index) {
        for (int n = 1; n < enabled.length; n++) {
            int candidate = 1 + ((Math.max(1, index) - 1 + n) % (enabled.length - 1));
            if (enabled[candidate]) return candidate;
        }
        return -1;
    }

    static CategorySettings load(Context context) {
        File file = new File(context.getFilesDir(), "categories.json");
        CategorySettings defaults = defaults();
        if (!file.exists() && !new File(file.getPath() + ".bak").exists()) {
            Map<String, String> legacy = GenreNames.getDisplayNames(context);
            String[] labels = defaults.names.clone();
            for (int i = 0; i < labels.length; i++) {
                String value = legacy.get(MainActivity.GENRES.get(i));
                if (value != null) labels[i] = value;
            }
            return new CategorySettings(labels, defaults.enabled);
        }
        try {
            JSONObject json = new JSONObject(new String(new AtomicFile(file).readFully(), "UTF-8"));
            String[] labels = defaults.names.clone();
            boolean[] visible = defaults.enabled.clone();
            for (int i = 0; i < labels.length; i++) {
                JSONObject entry = json.optJSONObject(MainActivity.GENRES.get(i));
                if (entry != null) {
                    labels[i] = entry.optString("name", labels[i]);
                    visible[i] = entry.optBoolean("visible", true);
                }
            }
            return new CategorySettings(labels, visible);
        } catch (Exception error) {
            Log.w("OUYAlaunch", "Cannot read categories; using safe defaults without overwriting file", error);
            return defaults;
        }
    }

    void save(Context context) throws IOException {
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "categories.json"));
        FileOutputStream stream = null;
        try {
            JSONObject json = new JSONObject();
            for (int i = 0; i < names.length; i++) {
                JSONObject entry = new JSONObject();
                entry.put("name", names[i]);
                entry.put("visible", enabled[i]);
                json.put(MainActivity.GENRES.get(i), entry);
            }
            byte[] bytes = json.toString(2).getBytes("UTF-8");
            stream = file.startWrite();
            stream.write(bytes);
            file.finishWrite(stream);
        } catch (Exception error) {
            if (stream != null) file.failWrite(stream);
            throw new IOException("Cannot save categories", error);
        }
    }
}
