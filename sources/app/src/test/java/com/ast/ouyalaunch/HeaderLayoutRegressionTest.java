package com.ast.ouyalaunch;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Run with ./gradlew :app:testDebugUnitTest in the project's Android build environment. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 28}, qualifiers = "w1920dp-h1080dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class HeaderLayoutRegressionTest {
    @Before public void timedFrames() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
    }

    @Test public void barsLogoNetworkAndDividerUseRequestedGeometry() throws Exception {
        ActivityController<MainActivity> owner = start(false, 1920, 1080);
        MainActivity activity = owner.get();
        try {
            View header = activity.findViewById(R.id.header);
            View divider = activity.findViewById(R.id.header_divider);
            View tabs = activity.findViewById(R.id.tabs_bar);
            View recycler = activity.findViewById(R.id.recycler);
            ImageView logo = activity.findViewById(R.id.header_logo);
            ImageView network = activity.findViewById(R.id.imgNetworkStatus);
            assertEquals(100, header.getHeight());
            assertEquals(header.getHeight(), tabs.getHeight());
            assertEquals(2, divider.getHeight());
            assertEquals(header.getBottom(), divider.getTop());
            assertEquals(divider.getBottom(), tabs.getTop());
            assertEquals(tabs.getBottom(), recycler.getTop());
            assertEquals(0, divider.getLeft());
            assertEquals(1920, divider.getRight());
            assertEquals(0xFFFF2B00, ((ColorDrawable) divider.getBackground()).getColor());
            assertEquals(0xAA000000, ((ColorDrawable) header.getBackground()).getColor());
            assertEquals(0xAA000000, ((ColorDrawable) tabs.getBackground()).getColor());
            assertSame(header, logo.getParent());
            assertSame(header, network.getParent());
            assertEquals(215, logo.getWidth());
            assertEquals(60, logo.getHeight());
            assertEquals(16, logo.getLeft());
            assertEquals(100, logo.getTop() + logo.getBottom());
            assertEquals(100, network.getTop() + network.getBottom());
            assertEquals(30, header.getWidth() - network.getRight());
            assertEquals(64, network.getWidth());
            assertEquals(64, network.getHeight());
            assertEquals(0f, network.getRotation(), 0f);
        } finally { owner.pause().stop().destroy(); }
    }

    @Test public void tabViewportMatchesFirstAndLastUnscaledTileEdges() throws Exception {
        verifyTileAlignment(1920, 1080);
    }

    @Test
    @Config(sdk = {16, 28}, qualifiers = "w960dp-h540dp-land-xhdpi")
    public void alignmentFollowsExistingHighDensityGridFallback() throws Exception {
        verifyTileAlignment(1920, 1080);
    }

    @Test
    @Config(sdk = {16, 28}, qualifiers = "w1280dp-h720dp-land-mdpi")
    public void alignmentFollowsExistingTwoColumnFallback() throws Exception {
        verifyTileAlignment(1280, 720);
    }

    @Test public void emptyPageKeepsTheSameFullRowWidth() throws Exception {
        ActivityController<MainActivity> owner = start(true, 1920, 1080);
        MainActivity activity = owner.get();
        try {
            View viewport = activity.findViewById(R.id.tab_scroll);
            int left = viewport.getLeft(), right = viewport.getRight();
            RecyclerView recycler = activity.findViewById(R.id.recycler);
            AppAdapter adapter = (AppAdapter) recycler.getAdapter();
            adapter.replacePage(Collections.emptyList());
            frames(activity, 12, 1920, 1080);
            assertEquals(0, recycler.getChildCount());
            assertEquals(left, viewport.getLeft());
            assertEquals(right, viewport.getRight());
        } finally { owner.pause().stop().destroy(); }
    }

    @Test public void visibleTabsFillRowAndKeepTypographyWhenOthersAreHidden() throws Exception {
        ActivityController<MainActivity> owner = start(false, 1920, 1080);
        MainActivity activity = owner.get();
        try {
            SlidingTabLayout tabs = activity.findViewById(R.id.tab_container);
            View viewport = activity.findViewById(R.id.tab_scroll);
            int beforeLeft = viewport.getLeft(), beforeRight = viewport.getRight();
            assertEquals(viewport.getWidth(), tabs.getWidth());
            assertEquals(0, tabs.getChildAt(0).getLeft());
            assertEquals(tabs.getWidth(), tabs.getChildAt(6).getRight());
            String[] names = MainActivity.GENRES.toArray(new String[0]);
            names[2] = "Arcade";
            CategorySettings settings = new CategorySettings(names,
                    new boolean[] {true, false, true, false, false, false, false});
            Method apply = MainActivity.class.getDeclaredMethod("applyCategories", CategorySettings.class);
            apply.setAccessible(true);
            apply.invoke(activity, settings);
            frames(activity, 12, 1920, 1080);
            assertEquals(beforeLeft, viewport.getLeft());
            assertEquals(beforeRight, viewport.getRight());
            assertEquals(0, tabs.getChildAt(0).getLeft());
            assertEquals(tabs.getWidth(), tabs.getChildAt(2).getRight());
            assertEquals(View.GONE, tabs.getChildAt(1).getVisibility());
            TextView label = (TextView) tabs.getChildAt(2);
            assertEquals("Arcade", label.getText().toString());
            assertEquals(Gravity.CENTER, label.getGravity());
            assertEquals(18f, label.getTextSize(), .01f);
            assertEquals(0xFFEEEEEE, label.getTextColors().getDefaultColor());
            assertEquals(32, label.getPaddingLeft());
            assertEquals(32, label.getPaddingRight());
            assertEquals(0, label.getPaddingTop());
            assertEquals(0, label.getPaddingBottom());
            assertEquals(100, label.getHeight());
            assertEquals(1f, ((LinearLayout.LayoutParams) label.getLayoutParams()).weight, 0f);
            assertNull(label.getBackground());
        } finally { owner.pause().stop().destroy(); }
    }

    @Test public void everyTopRowColumnStillRetainsControllerFocus() throws Exception {
        ActivityController<MainActivity> owner = start(true, 1920, 1080);
        MainActivity activity = owner.get();
        try {
            RecyclerView recycler = activity.findViewById(R.id.recycler);
            GridLayoutManager grid = (GridLayoutManager) recycler.getLayoutManager();
            for (int column = 0; column < grid.getSpanCount(); column++) {
                View tile = grid.findViewByPosition(column);
                assertNotNull(tile);
                tile.requestFocus();
                for (int repeat = 0; repeat < 5; repeat++) {
                    assertSame(tile, recycler.focusSearch(tile, View.FOCUS_UP));
                    activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
                    activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));
                    assertTrue(tile.hasFocus());
                }
            }
        } finally { owner.pause().stop().destroy(); }
    }

    @Test public void compactGridFitsThreeRowsWithoutFocusScrolling() throws Exception {
        verifyCompactGrid();
    }

    @Test
    @Config(sdk = {16, 28}, qualifiers = "w960dp-h540dp-land-xhdpi")
    public void compactGridKeepsEqualGapsAtHighDensity() throws Exception {
        verifyCompactGrid();
    }

    @Test
    @Config(sdk = 28, qualifiers = "w480dp-h270dp-land-xxxhdpi")
    public void compactGridTopPaddingDoesNotGrowWithDensity() throws Exception {
        verifyCompactGrid();
    }

    private static void verifyCompactGrid() throws Exception {
        ActivityController<MainActivity> owner = start(true, 1920, 1080);
        MainActivity activity = owner.get();
        try {
            RecyclerView recycler = activity.findViewById(R.id.recycler);
            GridLayoutManager grid = (GridLayoutManager) recycler.getLayoutManager();
            assertEquals(3, grid.getSpanCount());
            assertEquals(878, recycler.getHeight());
            assertEquals(6, recycler.getPaddingTop());
            assertEquals(8, recycler.getPaddingBottom());
            View first = grid.findViewByPosition(0);
            View nextColumn = grid.findViewByPosition(1);
            View secondRow = grid.findViewByPosition(3);
            View thirdRow = grid.findViewByPosition(6);
            assertNotNull(first);
            assertNotNull(nextColumn);
            assertNotNull(secondRow);
            assertNotNull(thirdRow);
            assertEquals(440, first.getWidth());
            assertEquals(248, first.getHeight());
            assertEquals(40, nextColumn.getLeft() - first.getRight());
            assertEquals(40, secondRow.getTop() - first.getBottom());
            assertEquals(40, thirdRow.getTop() - secondRow.getBottom());
            assertTrue(grid.getDecoratedBottom(thirdRow)
                    <= recycler.getHeight() - recycler.getPaddingBottom());
            int initialTop = first.getTop();
            int initialScroll = recycler.computeVerticalScrollOffset();
            // Move through every tile in the three visible rows in both directions.
            // RequestFocus exercises the existing RecyclerView focus-rectangle path;
            // neither the focus animation nor a visible row may move the viewport.
            for (int pass = 0; pass < 2; pass++) {
                for (int step = 0; step < 9; step++) {
                    int position = pass == 0 ? step : 8 - step;
                    View tile = grid.findViewByPosition(position);
                    assertNotNull(tile);
                    assertTrue(tile.requestFocus());
                    frames(activity, 10, 1920, 1080);
                    assertTrue(tile.hasFocus());
                    assertEquals("Visible-row focus must not move the viewport",
                            initialScroll, recycler.computeVerticalScrollOffset());
                    assertEquals(initialTop, grid.findViewByPosition(0).getTop());
                }
            }
            // Real scrolling remains available: the fourth row must still be reachable.
            View third = grid.findViewByPosition(6);
            third.requestFocus();
            View fourth = recycler.focusSearch(third, View.FOCUS_DOWN);
            assertNotNull(fourth);
            assertEquals(9, recycler.getChildAdapterPosition(fourth));
            assertTrue(fourth.requestFocus());
            frames(activity, 10, 1920, 1080);
            assertTrue(recycler.computeVerticalScrollOffset() > initialScroll);
            assertTrue(fourth.hasFocus());
            // The last viewport must be aligned too, not just the initial three rows.
            assertEquals(3, grid.findFirstVisibleItemPosition());
            assertEquals(initialTop, grid.findViewByPosition(3).getTop());
            assertEquals(6, grid.getDecoratedTop(grid.findViewByPosition(3)));
        } finally { owner.pause().stop().destroy(); }
    }

    private static void verifyTileAlignment(int width, int height) throws Exception {
        ActivityController<MainActivity> owner = start(true, width, height);
        MainActivity activity = owner.get();
        try {
            RecyclerView recycler = activity.findViewById(R.id.recycler);
            GridLayoutManager grid = (GridLayoutManager) recycler.getLayoutManager();
            View bar = activity.findViewById(R.id.tabs_bar);
            View viewport = activity.findViewById(R.id.tab_scroll);
            View first = grid.findViewByPosition(0);
            View last = grid.findViewByPosition(grid.getSpanCount() - 1);
            assertNotNull(first);
            assertNotNull(last);
            assertEquals(recycler.getLeft() + first.getLeft(), bar.getLeft() + viewport.getLeft());
            assertEquals(recycler.getLeft() + last.getRight(), bar.getLeft() + viewport.getRight());
            int left = viewport.getLeft(), right = viewport.getRight();
            last.requestFocus();
            frames(activity, 12, width, height);
            assertEquals(left, viewport.getLeft());
            assertEquals(right, viewport.getRight());
            // Repeat layouts: no drift or repeated margin-induced layout requests.
            frames(activity, 12, width, height);
            assertEquals(left, viewport.getLeft());
            assertEquals(right, viewport.getRight());
        } finally { owner.pause().stop().destroy(); }
    }

    private static ActivityController<MainActivity> start(boolean games, int width, int height) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        CategorySettings.defaults().save(context);
        List<AppEntry> entries = new ArrayList<>();
        if (games) for (int i = 0; i < 12; i++) {
            entries.add(new AppEntry("header.game." + i, "Game " + i, "Action", true, null));
        }
        new DataStore(context).save(entries);
        ActivityController<MainActivity> owner = Robolectric.buildActivity(MainActivity.class).setup().visible();
        for (int i = 0; i < 200; i++) {
            frames(owner.get(), 1, width, height);
            if (owner.get().findViewById(R.id.overlay_loading).getVisibility() == View.GONE) {
                frames(owner.get(), 24, width, height);
                return owner;
            }
            Thread.sleep(5);
        }
        owner.pause().stop().destroy();
        throw new AssertionError("Initial page did not become ready");
    }

    private static void frames(MainActivity activity, int count, int width, int height) {
        for (int i = 0; i < count; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS);
            View root = activity.getWindow().getDecorView();
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            root.getViewTreeObserver().dispatchOnPreDraw();
        }
    }
}
