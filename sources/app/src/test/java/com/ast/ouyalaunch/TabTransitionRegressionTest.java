package com.ast.ouyalaunch;

import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 28})
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
public class TabTransitionRegressionTest {
    @org.junit.Before public void useTimedAnimationFrames() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
    }
    @Test public void reverseDuringPreparationRestoresOriginalPageAndRejectsQueuedWork() throws Exception {
        ActivityController<MainActivity> owner = start();
        MainActivity activity = owner.get();
        try {
            View grid = activity.findViewById(R.id.recycler);
            SlidingTabLayout tabs = activity.findViewById(R.id.tab_container);
            press(activity, KeyEvent.KEYCODE_BUTTON_R1);
            assertEquals(0f, grid.getAlpha(), 0f);
            assertTrue(tabs.getChildAt(1).isSelected());
            press(activity, KeyEvent.KEYCODE_BUTTON_L1);
            assertEquals(1f, grid.getAlpha(), 0f);
            frames(activity, 12);
            assertTrue(tabs.getChildAt(0).isSelected());
            assertEquals(1f, grid.getAlpha(), .01f);
        } finally { owner.pause().stop().destroy(); }
    }

    @Test public void rapidSwitchShowsOnlyLatestTabAfterLayoutAndFade() throws Exception {
        ActivityController<MainActivity> owner = start();
        MainActivity activity = owner.get();
        try {
            View grid = activity.findViewById(R.id.recycler);
            SlidingTabLayout tabs = activity.findViewById(R.id.tab_container);
            press(activity, KeyEvent.KEYCODE_BUTTON_R1);
            press(activity, KeyEvent.KEYCODE_BUTTON_R1);
            assertEquals(0f, grid.getAlpha(), 0f);
            assertTrue(tabs.getChildAt(2).isSelected());
            frames(activity, 6);
            assertTrue("Loaded tiles should still be fading in, alpha=" + grid.getAlpha(), grid.getAlpha() > 0f && grid.getAlpha() < 1f);
            frames(activity, 24);
            assertTrue(tabs.getChildAt(2).isSelected());
            assertFalse(tabs.getChildAt(1).isSelected());
            assertEquals(1f, grid.getAlpha(), .01f);
            assertNull(tabs.getChildAt(2).getBackground());
        } finally { owner.pause().stop().destroy(); }
    }

    private static ActivityController<MainActivity> start() throws Exception {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        CategorySettings.defaults().save(context);
        new DataStore(context).save(Collections.emptyList());
        ActivityController<MainActivity> owner = Robolectric.buildActivity(MainActivity.class).setup().visible();
        for (int i = 0; i < 200; i++) {
            frames(owner.get(), 1);
            if (owner.get().findViewById(R.id.overlay_loading).getVisibility() == View.GONE) {
                frames(owner.get(), 20);
                return owner;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Initial page did not become ready");
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
