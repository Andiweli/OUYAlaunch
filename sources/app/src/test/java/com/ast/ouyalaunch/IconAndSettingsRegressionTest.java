package com.ast.ouyalaunch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.widget.ImageView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 28})
public class IconAndSettingsRegressionTest {
    private Context context;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        new File(context.getFilesDir(), "categories.json").delete();
        new File(context.getFilesDir(), "categories.json.bak").delete();
    }

    @Test public void keyboardResizeKeepsFocusedNameInsideViewport() {
        org.robolectric.android.controller.ActivityController<android.app.Activity> owner =
                org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup();
        android.app.Activity activity = owner.get();
        CategoryDialog.EditorScrollView scroll = new CategoryDialog.EditorScrollView(activity);
        android.widget.LinearLayout rows = new android.widget.LinearLayout(activity);
        rows.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.EditText last = null;
        for (int i = 0; i < 8; i++) {
            last = new android.widget.EditText(activity);
            last.setSingleLine(true);
            last.setText("Category " + i);
            rows.addView(last, new android.widget.LinearLayout.LayoutParams(400, 60));
        }
        scroll.addView(rows);
        activity.setContentView(scroll);
        owner.visible();
        try {
            scroll.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY));
            scroll.layout(0, 0, 400, 300);
            last.requestFocus();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            // Simulate a keyboard reducing the editor's available viewport.
            scroll.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY));
            scroll.layout(0, 0, 400, 100);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            android.graphics.Rect bounds = new android.graphics.Rect();
            last.getDrawingRect(bounds);
            scroll.offsetDescendantRectToMyCoords(last, bounds);
            assertTrue(last.hasFocus());
            assertTrue(bounds.top >= scroll.getScrollY());
            assertTrue(bounds.bottom <= scroll.getScrollY() + scroll.getHeight());
        } finally { owner.pause().stop().destroy(); }
    }

    @Test public void pauseAndResumeKeepDecodedIconInCache() throws Exception {
        File file = image("retained.png");
        IconLoader loader = new IconLoader();
        try {
            ImageView first = new ImageView(context);
            loader.load(first, file.getAbsolutePath());
            await(() -> first.getDrawable() != null);
            Bitmap original = ((BitmapDrawable) first.getDrawable()).getBitmap();
            loader.setPaused(true);
            assertNotNull(first.getDrawable());
            assertTrue(file.delete());
            loader.setPaused(false);
            ImageView second = new ImageView(context);
            loader.load(second, file.getAbsolutePath());
            assertNotNull("Must bind synchronously from cache, without reopening the removed file", second.getDrawable());
            assertSame(original, ((BitmapDrawable) second.getDrawable()).getBitmap());
        } finally { loader.close(); }
    }

    @Test public void sameViewDoesNotBlinkEvenAfterMemoryCacheEviction() throws Exception {
        File file = image("bound.png");
        IconLoader loader = new IconLoader();
        try {
            ImageView view = new ImageView(context);
            loader.load(view, file.getAbsolutePath());
            await(() -> view.getDrawable() != null);
            Bitmap original = ((BitmapDrawable) view.getDrawable()).getBitmap();
            loader.trim(true);
            file.delete();
            loader.load(view, file.getAbsolutePath());
            assertNotNull(view.getDrawable());
            assertSame(original, ((BitmapDrawable) view.getDrawable()).getBitmap());
        } finally { loader.close(); }
    }

    @Test public void viewportIsReadyBeforeItsCallbackRuns() throws Exception {
        File first = image("first.png"), second = image("second.png");
        List<AppEntry> entries = Arrays.asList(entry(first), entry(second));
        IconLoader loader = new IconLoader();
        AtomicInteger ready = new AtomicInteger();
        try {
            loader.prepare(entries, 0, 2, () -> {
                for (AppEntry entry : entries) {
                    ImageView view = new ImageView(context);
                    loader.load(view, entry.iconPath);
                    assertNotNull("A prepared viewport must bind without empty tiles", view.getDrawable());
                }
                ready.incrementAndGet();
            });
            await(() -> ready.get() == 1);
            assertEquals(1, ready.get());
        } finally { loader.close(); }
    }

    @Test public void replacedPreparationCannotPublishOldPage() throws Exception {
        IconLoader loader = new IconLoader();
        AtomicInteger old = new AtomicInteger(), latest = new AtomicInteger();
        try {
            loader.setPaused(true);
            loader.prepare(Collections.singletonList(entry(image("old.png"))), 0, 1, old::incrementAndGet);
            loader.prepare(Collections.singletonList(entry(image("new.png"))), 0, 1, latest::incrementAndGet);
            loader.setPaused(false);
            await(() -> latest.get() == 1);
            assertEquals(0, old.get());
        } finally { loader.close(); }
    }

    @Test public void missingIconDoesNotBlockPagePreparation() throws Exception {
        IconLoader loader = new IconLoader();
        AtomicInteger ready = new AtomicInteger();
        try {
            loader.prepare(Collections.singletonList(entry(new File(context.getCacheDir(), "missing.png"))),
                    0, 1, ready::incrementAndGet);
            await(() -> ready.get() == 1);
        } finally { loader.close(); }
    }

    @Test public void namesAndVisibilityPersistWithoutChangingGameAssignments() throws Exception {
        String[] names = MainActivity.GENRES.toArray(new String[0]);
        boolean[] enabled = {true, false, true, false, false, true, false};
        names[0] = "Meine Spiele";
        names[2] = "  Arcade  ";
        CategorySettings settings = new CategorySettings(names, enabled);
        DataStore store = new DataStore(context);
        AppEntry hidden = new AppEntry("game.hidden", "Hidden", "Casual", true, null);
        store.save(Collections.singletonList(hidden));
        settings.save(context);
        CategorySettings loaded = CategorySettings.load(context);
        assertEquals("Meine Spiele", loaded.name(0));
        assertEquals("Arcade", loaded.name(2));
        assertFalse(loaded.enabled(1));
        assertEquals(2, loaded.neighbor(0, 1));
        assertEquals(5, loaded.neighbor(2, 1));
        assertEquals(0, loaded.neighbor(2, -1));
        assertEquals(5, loaded.nextAssignable(2));
        assertEquals(2, loaded.nextAssignable(5));
        assertEquals("Casual", store.load().get(0).genre);
        assertTrue(store.load().get(0).favorite);
    }

    @Test public void lastCategoryCannotBeDisabled() {
        try {
            new CategorySettings(MainActivity.GENRES.toArray(new String[0]), new boolean[7]);
            fail("At least one category is required");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void favoritesOnlyDoesNotAssignGamesToHiddenGenres() {
        CategorySettings settings = new CategorySettings(MainActivity.GENRES.toArray(new String[0]),
                new boolean[] {true, false, false, false, false, false, false});
        assertEquals(-1, settings.nextAssignable(2));
        assertEquals(0, settings.neighbor(0, 1));
    }

    @Test public void settingsRecoverPreviousVersionAfterInterruptedWrite() throws Exception {
        CategorySettings.defaults().save(context);
        androidx.core.util.AtomicFile file = new androidx.core.util.AtomicFile(new File(context.getFilesDir(), "categories.json"));
        FileOutputStream stream = file.startWrite();
        stream.write("{partial".getBytes("UTF-8"));
        stream.close();
        assertEquals("Favorites", CategorySettings.load(context).name(0));
        assertTrue(CategorySettings.load(context).enabled(6));
    }

    @Test public void ouyaMenuOpensEditorAndSavedChoicesUpdateTabs() throws Exception {
        new DataStore(context).save(Collections.emptyList());
        org.robolectric.android.controller.ActivityController<MainActivity> controller =
                org.robolectric.Robolectric.buildActivity(MainActivity.class).setup().visible();
        MainActivity activity = controller.get();
        try {
            await(() -> activity.findViewById(R.id.overlay_loading).getVisibility() == android.view.View.GONE);
            activity.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_MENU));
            android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            // Dialog.OnShow installs the custom save action in the next main-loop turn.
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            android.widget.CheckBox first = dialog.findViewById(0x00f10000);
            assertEquals("Favorites", first.getText().toString());
            for (int i = 1; i < MainActivity.GENRES.size(); i++) {
                android.widget.CheckBox slot = dialog.findViewById(0x00f10000 + i * 2);
                assertEquals("Tab " + i, slot.getText().toString());
            }
            assertTrue(first.isChecked());
            first.dispatchKeyEvent(new android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_BUTTON_A, 0));
            first.dispatchKeyEvent(new android.view.KeyEvent(0, 1, android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_BUTTON_A, 1));
            first.dispatchKeyEvent(new android.view.KeyEvent(0, 2, android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_BUTTON_A, 0));
            assertFalse(first.isChecked());
            android.widget.EditText name = dialog.findViewById(0x00f10005);
            name.requestFocus();
            assertEquals("Focusing must not select the whole name", name.getSelectionStart(), name.getSelectionEnd());
            name.setSelection(3);
            name.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_BUTTON_A));
            name.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_BUTTON_A));
            assertEquals(3, name.getSelectionStart());
            assertEquals(3, name.getSelectionEnd());
            name.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT));
            name.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT));
            assertEquals(2, name.getSelectionStart());
            name.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
            name.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
            assertEquals(3, name.getSelectionStart());
            name.getText().replace(3, 4, "X");
            assertEquals("ActXon", name.getText().toString());
            name.setText("Arcade Test");
            ((androidx.appcompat.app.AlertDialog) dialog).getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
            await(() -> !dialog.isShowing());
            android.widget.LinearLayout tabs = activity.findViewById(R.id.tab_container);
            assertEquals(android.view.View.GONE, tabs.getChildAt(0).getVisibility());
            assertEquals("Arcade Test", ((android.widget.TextView) tabs.getChildAt(2)).getText().toString());
            assertEquals("Arcade Test", CategorySettings.load(context).name(2));
        } finally { controller.pause().stop().destroy(); }
    }

    private AppEntry entry(File file) { return new AppEntry(file.getName(), file.getName(), "Casual", false, file.getAbsolutePath()); }
    private File image(String name) throws Exception {
        File file = new File(context.getCacheDir(), name);
        Bitmap bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.BLUE);
        try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        bitmap.recycle();
        return file;
    }
    private interface Condition { boolean ready(); }
    private void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.ready() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Timed out waiting for background image preparation", condition.ready());
    }
}
