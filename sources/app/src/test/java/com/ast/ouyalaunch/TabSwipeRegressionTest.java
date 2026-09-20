package com.ast.ouyalaunch;

import android.content.Context;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Android integration tests; run in the normal Gradle/Android SDK build environment. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 28}, qualifiers = "w1920dp-h1080dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class TabSwipeRegressionTest {
    @Before public void timedFrames() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
    }

    @Test public void initialLoadFadesWithoutHorizontalMotion() throws Exception {
        ActivityController<MainActivity> owner = start(false);
        try {
            MainActivity activity = owner.get();
            for (int i = 0; i < 24; i++) {
                assertEquals(0f, recycler(activity).getTranslationX(), 0f);
                frames(activity, 1);
            }
            assertSettled(activity);
        } finally { owner.pause().stop().destroy(); }
    }

    @Test public void rightAndLeftEnterFromOppositeSidesAndKeepTheRowRaster() throws Exception {
        ActivityController<MainActivity> owner = start(true);
        try {
            MainActivity activity = owner.get();
            View tabViewport = activity.findViewById(R.id.tab_scroll);
            int tabLeft = tabViewport.getLeft(), tabRight = tabViewport.getRight();
            int gridTop = recycler(activity).getTop();
            press(activity, KeyEvent.KEYCODE_BUTTON_R1);
            awaitReveal(activity, 1);
            assertEntrance(activity, 1);
            assertRowAligned(activity);
            frames(activity, 5);
            assertTrue(recycler(activity).getTranslationX() > 0f);
            assertTrue(recycler(activity).getTranslationX() < 44f);
            assertTrue(recycler(activity).getAlpha() > 0f);
            assertTrue(recycler(activity).getAlpha() < 1f);
            assertEquals(gridTop, recycler(activity).getTop());
            assertEquals(tabLeft, tabViewport.getLeft());
            assertEquals(tabRight, tabViewport.getRight());
            frames(activity, 24);
            assertSettled(activity);
            press(activity, KeyEvent.KEYCODE_BUTTON_L1);
            awaitReveal(activity, 0);
            assertEntrance(activity, -1);
            frames(activity, 24);
            assertSettled(activity);
            assertRowAligned(activity);
        } finally { owner.pause().stop().destroy(); }
    }

    @Test public void rapidRightRightLeftUsesTheLastNavigationDirection() throws Exception {
        ActivityController<MainActivity> owner = start(true);
        try {
            MainActivity activity = owner.get();
            press(activity, KeyEvent.KEYCODE_BUTTON_R1);
            press(activity, KeyEvent.KEYCODE_BUTTON_R1);
            press(activity, KeyEvent.KEYCODE_BUTTON_L1);
            awaitReveal(activity, 1);
            assertEntrance(activity, -1);
            frames(activity, 24);
            assertSettled(activity);
            assertEquals(1, intField(activity, "currentTab"));
        } finally { owner.pause().stop().destroy(); }
    }

    @Test public void reversingBeforePreparationRestoresTheOriginalPageWithoutOffset() throws Exception {
        ActivityController<MainActivity> owner = start(true);
        try {
            MainActivity activity = owner.get();
            press(activity, KeyEvent.KEYCODE_BUTTON_R1);
            assertEquals(0f, recycler(activity).getAlpha(), 0f);
            press(activity, KeyEvent.KEYCODE_BUTTON_L1);
            assertSettled(activity);
            frames(activity, 30);
            assertEquals(0, intField(activity, "currentTab"));
            assertSettled(activity);
        } finally { owner.pause().stop().destroy(); }
    }

    @Test public void interruptingARunningSwipeDoesNotAccumulateTranslation() throws Exception {
        ActivityController<MainActivity> owner = start(true);
        try {
            MainActivity activity = owner.get();
            press(activity, KeyEvent.KEYCODE_BUTTON_R1);
            awaitReveal(activity, 1);
            frames(activity, 4);
            assertTrue(recycler(activity).getTranslationX() > 0f);
            press(activity, KeyEvent.KEYCODE_BUTTON_L1);
            assertEquals(0f, recycler(activity).getTranslationX(), 0f);
            awaitReveal(activity, 0);
            assertEntrance(activity, -1);
            frames(activity, 24);
            assertSettled(activity);
        } finally { owner.pause().stop().destroy(); }
    }

    @Test public void clickingThePendingTabAgainDoesNotRestartOrLoseDirection() throws Exception {
        ActivityController<MainActivity> owner = start(true);
        try {
            MainActivity activity = owner.get();
            SlidingTabLayout tabs = activity.findViewById(R.id.tab_container);
            tabs.getChildAt(3).performClick();
            int ticket = intField(activity, "pageGeneration");
            tabs.getChildAt(3).performClick();
            assertEquals(ticket, intField(activity, "pageGeneration"));
            awaitReveal(activity, 3);
            assertEntrance(activity, 1);
            frames(activity, 24);
            tabs.getChildAt(1).performClick();
            awaitReveal(activity, 1);
            assertEntrance(activity, -1);
            frames(activity, 24);
            assertSettled(activity);
        } finally { owner.pause().stop().destroy(); }
    }

    @Test public void pausingDuringAnEntranceClearsTheVisualOffset() throws Exception {
        ActivityController<MainActivity> owner = start(true);
        boolean resumed = true;
        try {
            MainActivity activity = owner.get();
            press(activity, KeyEvent.KEYCODE_BUTTON_R1);
            awaitReveal(activity, 1);
            frames(activity, 4);
            assertTrue(recycler(activity).getTranslationX() > 0f);
            owner.pause();
            resumed = false;
            assertSettled(activity);
            owner.resume();
            resumed = true;
            frames(activity, 24);
            assertSettled(activity);
            assertRowAligned(activity);
        } finally {
            if (resumed) owner.pause();
            owner.stop().destroy();
        }
    }

    @Test public void scrollAndTabRestoreKeepTheirOffsetsWithoutReplayingTheSwipe() throws Exception {
        ActivityController<MainActivity> owner = start(true);
        try {
            MainActivity activity = owner.get();
            RecyclerView recycler = recycler(activity);
            GridLayoutManager grid = (GridLayoutManager) recycler.getLayoutManager();
            press(activity, KeyEvent.KEYCODE_BUTTON_R1);
            awaitReveal(activity, 1);
            frames(activity, 24);
            recycler.scrollBy(0, 288);
            frames(activity, 8);
            assertEquals(3, grid.findFirstVisibleItemPosition());
            View first = grid.findViewByPosition(3);
            assertNotNull(first);
            first.requestFocus();
            frames(activity, 8);
            assertRowAligned(activity);
            assertSettled(activity);
            press(activity, KeyEvent.KEYCODE_BUTTON_R1);
            awaitReveal(activity, 2);
            frames(activity, 24);
            press(activity, KeyEvent.KEYCODE_BUTTON_L1);
            awaitReveal(activity, 1);
            assertEntrance(activity, -1);
            assertEquals(3, grid.findFirstVisibleItemPosition());
            assertRowAligned(activity);
            frames(activity, 24);
            assertSettled(activity);
        } finally { owner.pause().stop().destroy(); }
    }

    private static ActivityController<MainActivity> start(boolean finishFade) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        CategorySettings.defaults().save(context);
        List<AppEntry> entries = new ArrayList<>();
        for (int tab = 1; tab <= 3; tab++) {
            for (int i = 0; i < 24; i++) {
                entries.add(new AppEntry("test.swipe." + tab + ".game" + i,
                        String.format(Locale.ROOT, "Game %d-%02d", tab, i),
                        MainActivity.GENRES.get(tab), true, null));
            }
        }
        new DataStore(context).save(entries);
        ActivityController<MainActivity> owner = Robolectric.buildActivity(MainActivity.class).setup().visible();
        try {
            awaitReveal(owner.get(), 0);
            if (finishFade) frames(owner.get(), 24);
            return owner;
        } catch (Exception | AssertionError error) {
            owner.pause().stop().destroy();
            throw error;
        }
    }

    private static RecyclerView recycler(MainActivity activity) {
        return activity.findViewById(R.id.recycler);
    }

    private static void awaitReveal(MainActivity activity, int tab) throws Exception {
        for (int i = 0; i < 200; i++) {
            frames(activity, 1);
            if (activity.findViewById(R.id.overlay_loading).getVisibility() == View.GONE
                    && !booleanField(activity, "preparingPage") && intField(activity, "currentTab") == tab) return;
            Thread.sleep(5);
        }
        throw new AssertionError("Page " + tab + " did not become ready");
    }

    private static void assertEntrance(MainActivity activity, int sign) {
        float offset = recycler(activity).getTranslationX();
        assertTrue("Wrong entrance direction: " + offset, offset * sign > 0f);
        assertTrue("Swipe must remain subtle: " + offset, Math.abs(offset) <= 44.01f);
        assertEquals(0f, recycler(activity).getTranslationY(), 0f);
    }

    private static void assertSettled(MainActivity activity) {
        assertEquals(1f, recycler(activity).getAlpha(), .01f);
        assertEquals(0f, recycler(activity).getTranslationX(), .01f);
        assertEquals(0f, recycler(activity).getTranslationY(), 0f);
    }

    private static void assertRowAligned(MainActivity activity) {
        RecyclerView recycler = recycler(activity);
        GridLayoutManager grid = (GridLayoutManager) recycler.getLayoutManager();
        int position = grid.findFirstVisibleItemPosition();
        assertTrue(position >= 0);
        assertEquals(0, position % grid.getSpanCount());
        View first = grid.findViewByPosition(position);
        assertNotNull(first);
        assertEquals(26, first.getTop());
        View below = grid.findViewByPosition(position + grid.getSpanCount());
        if (below != null) assertEquals(40, below.getTop() - first.getBottom());
    }

    private static int intField(MainActivity activity, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(activity);
    }

    private static boolean booleanField(MainActivity activity, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(activity);
    }

    private static void press(MainActivity activity, int key) {
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    private static void frames(MainActivity activity, int count) {
        for (int i = 0; i < count; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS);
            View root = activity.getWindow().getDecorView();
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1920, 1080);
            root.getViewTreeObserver().dispatchOnPreDraw();
        }
    }
}
