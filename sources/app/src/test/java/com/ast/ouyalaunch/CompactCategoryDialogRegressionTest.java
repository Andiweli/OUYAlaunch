package com.ast.ouyalaunch;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.CompoundButtonCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Real Android-view tests. Run with :app:testDebugUnitTest in an Android build environment. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 28}, qualifiers = "w1920dp-h1080dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class CompactCategoryDialogRegressionTest {
    @Test public void sevenRowsAndActionsFitAt1080pMdpi() {
        verifySevenRows(1080);
    }

    @Test @Config(sdk = {16, 28}, qualifiers = "w960dp-h540dp-land-xhdpi")
    public void sevenRowsAndActionsFitAt1080pXhdpi() {
        verifySevenRows(1080);
    }

    @Test @Config(sdk = 28, qualifiers = "w480dp-h270dp-land-xxxhdpi")
    public void highDensityCannotRestoreOversizedThemeRows() {
        verifySevenRows(1080);
    }

    @Test @Config(sdk = {16, 28}, qualifiers = "w640dp-h360dp-land-xhdpi")
    public void sevenRowsAndActionsFitAt720pXhdpi() {
        verifySevenRows(720);
    }

    @Test public void dialogWrapsContentAndRetainsKeyboardResizeFlags() {
        try (Editor owner = new Editor()) {
            WindowManager.LayoutParams params = owner.dialog.getWindow().getAttributes();
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.height);
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    params.softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            assertEquals(0, params.flags & WindowManager.LayoutParams.FLAG_FULLSCREEN);
            assertEquals(0, params.flags & WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            assertEquals(owner.sizing.windowWidth, params.width);
        }
    }

    @Test public void themeCheckboxGraphicShrinksWithoutScalingItsHitTarget() {
        try (Editor owner = new Editor()) {
            layout(owner.dialog, 1080);
            CheckBox check = owner.dialog.findViewById(0x00f10000);
            Drawable graphic = CompoundButtonCompat.getButtonDrawable(check);
            assertNotNull(graphic);
            assertEquals(owner.sizing.px(22), graphic.getIntrinsicWidth());
            assertEquals(owner.sizing.px(22), graphic.getIntrinsicHeight());
            assertTrue(check.getHeight() > graphic.getIntrinsicHeight());
            assertEquals(1f, check.getScaleX(), 0f);
            assertEquals(1f, check.getScaleY(), 0f);
            assertEquals(owner.sizing.textPx(14), check.getTextSize(), .01f);
        }
    }

    @Test public void compactDrawableStillForwardsCheckedAndFocusedStates() {
        StateListDrawable theme = new StateListDrawable();
        Drawable off = new android.graphics.drawable.ColorDrawable(0xff777777);
        Drawable on = new android.graphics.drawable.ColorDrawable(0xffff8a00);
        theme.addState(new int[] {android.R.attr.state_checked}, on);
        theme.addState(new int[] {}, off);
        CategoryDialog.CompactCheckboxDrawable graphic =
                new CategoryDialog.CompactCheckboxDrawable(theme, 22);
        assertTrue(graphic.isStateful());
        graphic.setState(new int[] {android.R.attr.state_enabled, android.R.attr.state_checked,
                android.R.attr.state_focused});
        assertSame(on, theme.getCurrent());
        graphic.setState(new int[] {android.R.attr.state_enabled});
        assertSame(off, theme.getCurrent());
    }

    @Test public void controllerNavigationAndLetterEditingStayIntact() {
        try (Editor owner = new Editor()) {
            layout(owner.dialog, 1080);
            for (int i = 0; i < 7; i++) {
                CheckBox check = owner.dialog.findViewById(0x00f10000 + i * 2);
                EditText name = owner.dialog.findViewById(0x00f10001 + i * 2);
                assertEquals(name.getId(), check.getNextFocusRightId());
                if (i < 6) assertEquals(0x00f10000 + (i + 1) * 2, check.getNextFocusDownId());
                if (i > 0) assertEquals(0x00f10001 + (i - 1) * 2, name.getNextFocusUpId());
            }
            CheckBox check = owner.dialog.findViewById(0x00f10002);
            check.requestFocus();
            boolean before = check.isChecked();
            key(check, KeyEvent.KEYCODE_BUTTON_A);
            assertEquals(!before, check.isChecked());
            EditText name = owner.dialog.findViewById(0x00f10005);
            name.requestFocus();
            assertEquals(name.getSelectionStart(), name.getSelectionEnd());
            name.setSelection(3);
            key(name, KeyEvent.KEYCODE_DPAD_LEFT);
            assertEquals(2, name.getSelectionStart());
            key(name, KeyEvent.KEYCODE_DPAD_RIGHT);
            assertEquals(3, name.getSelectionStart());
            key(name, KeyEvent.KEYCODE_BUTTON_A);
            assertEquals(3, name.getSelectionStart());
            assertEquals(3, name.getSelectionEnd());
        }
    }

    @Test public void seventhNameScrollsIntoViewAfterKeyboardResize() {
        try (Editor owner = new Editor()) {
            layout(owner.dialog, 1080);
            EditText last = owner.dialog.findViewById(0x00f1000d);
            ScrollView scroll = parentScroll(last);
            int width = scroll.getWidth();
            last.requestFocus();
            // Exercise the same viewport size change delivered by ADJUST_RESIZE.
            int reducedHeight = Math.max(last.getHeight() * 2, 90);
            scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(reducedHeight, View.MeasureSpec.EXACTLY));
            scroll.layout(0, 0, width, reducedHeight);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(last.hasFocus());
            assertInScroll(scroll, last);
        }
    }

    @Test public void moveGenreTitleAndSubtitleShareTheExactLeftInset() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible();
        AlertDialog dialog = null;
        try {
            Activity activity = controller.get();
            activity.setTheme(R.style.AppTheme);
            dialog = CategoryDialog.showMoveGenre(activity, CategorySettings.defaults(), "Casual", genre -> {});
            assertNotNull(dialog);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            layout(dialog, 1080);
            View decor = dialog.getWindow().getDecorView();
            TextView title = findText(decor, "Move Genre");
            TextView subtitle = findText(decor, "Choose destination Genre");
            assertNotNull(title);
            assertNotNull(subtitle);
            int[] titlePosition = new int[2], subtitlePosition = new int[2];
            title.getLocationInWindow(titlePosition);
            subtitle.getLocationInWindow(subtitlePosition);
            assertEquals(titlePosition[0], subtitlePosition[0]);
        } finally {
            if (dialog != null) dialog.dismiss();
            controller.pause().stop().destroy();
        }
    }

    @Test public void brandAuthorVersionAndHintKeepTheRequestedAlignment() {
        try (Editor owner = new Editor()) {
            layout(owner.dialog, 1080);
            View decor = owner.dialog.getWindow().getDecorView();
            TextView brand = findText(decor, "OUYAlaunch");
            TextView author = findText(decor, "by Andreas 'Andiweli' Stürmer");
            TextView version = findText(decor, "(Version " + BuildConfig.VERSION_NAME + ")");
            TextView hint = findText(decor,
                    "Choose categories and edit their names. Hidden games keep their assignments.");
            assertNotNull(brand);
            assertNotNull(author);
            assertNotNull(version);
            assertNotNull(hint);
            assertSame(brand.getParent(), author.getParent());
            assertSame(brand.getParent(), version.getParent());
            assertEquals(brand.getTop() + brand.getBaseline(), author.getTop() + author.getBaseline());
            assertEquals(author.getTop() + author.getBaseline(), version.getTop() + version.getBaseline());
            assertTrue(author.getTextSize() < brand.getTextSize());

            int[] brandPosition = new int[2], hintPosition = new int[2], versionPosition = new int[2];
            brand.getLocationInWindow(brandPosition);
            hint.getLocationInWindow(hintPosition);
            version.getLocationInWindow(versionPosition);
            assertEquals(brandPosition[0], hintPosition[0]);

            int dialogRight = owner.dialog.getWindow().getAttributes().width;
            int expectedRightInset = owner.sizing.px(16);
            assertEquals(dialogRight - expectedRightInset, versionPosition[0] + version.getWidth());
        }
    }

    private void verifySevenRows(int screenHeight) {
        try (Editor owner = new Editor()) {
            // Include a conservative reserve for status/navigation bars on handhelds.
            layout(owner.dialog, screenHeight - owner.sizing.px(48));
            CheckBox first = owner.dialog.findViewById(0x00f10000);
            ScrollView scroll = parentScroll(first);
            assertEquals(0, scroll.getScrollY());
            assertTrue("All seven rows must fit without scrolling when the keyboard is hidden",
                    scroll.getChildAt(0).getHeight() <= scroll.getHeight());
            for (int i = 0; i < 7; i++) {
                CheckBox check = owner.dialog.findViewById(0x00f10000 + i * 2);
                EditText name = owner.dialog.findViewById(0x00f10001 + i * 2);
                assertEquals(i == 0 ? "Favorites" : "Tab " + i, check.getText().toString());
                assertInScroll(scroll, check);
                assertInScroll(scroll, name);
                assertEquals(check.getHeight(), name.getHeight());
            }
            View decor = owner.dialog.getWindow().getDecorView();
            assertVisibleWithin(decor, owner.dialog.getButton(AlertDialog.BUTTON_POSITIVE));
            assertVisibleWithin(decor, owner.dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
        }
    }

    private static void assertInScroll(ScrollView scroll, View child) {
        Rect rect = new Rect();
        child.getDrawingRect(rect);
        scroll.offsetDescendantRectToMyCoords(child, rect);
        assertTrue("Row is above the viewport", rect.top >= scroll.getScrollY());
        assertTrue("Row is clipped below the viewport",
                rect.bottom <= scroll.getScrollY() + scroll.getHeight());
    }

    private static void assertVisibleWithin(View ancestor, View child) {
        Rect rect = new Rect();
        child.getDrawingRect(rect);
        ((ViewGroup) ancestor).offsetDescendantRectToMyCoords(child, rect);
        assertTrue(child.getHeight() > 0);
        assertTrue(rect.top >= 0 && rect.bottom <= ancestor.getHeight());
    }

    private static ScrollView parentScroll(View child) {
        while (!(child instanceof ScrollView) && child.getParent() instanceof View) {
            child = (View) child.getParent();
        }
        assertTrue("Expected the editor's scroll container", child instanceof ScrollView);
        return (ScrollView) child;
    }

    private static TextView findText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) {
            return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void key(View view, int code) {
        view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    private static void layout(AlertDialog dialog, int availableHeight) {
        View decor = dialog.getWindow().getDecorView();
        int width = dialog.getWindow().getAttributes().width;
        for (int pass = 0; pass < 3; pass++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST));
            decor.layout(0, 0, width, decor.getMeasuredHeight());
        }
    }

    private static final class Editor implements AutoCloseable {
        final ActivityController<Activity> controller;
        final AlertDialog dialog;
        final CategoryDialogSizing sizing;

        Editor() {
            controller = Robolectric.buildActivity(Activity.class).setup().visible();
            Activity activity = controller.get();
            activity.setTheme(R.style.AppTheme);
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            sizing = CategoryDialogSizing.forDisplay(metrics.widthPixels, metrics.heightPixels,
                    metrics.density, metrics.scaledDensity);
            dialog = CategoryDialog.show(activity, CategorySettings.defaults(), updated -> {});
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }

        @Override public void close() {
            dialog.dismiss();
            controller.pause().stop().destroy();
        }
    }
}
