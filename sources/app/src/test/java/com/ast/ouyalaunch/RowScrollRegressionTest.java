package com.ast.ouyalaunch;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Looper;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Android integration checks; run with :app:testDebugUnitTest in an Android SDK environment. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 28}, qualifiers = "w1920dp-h1080dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class RowScrollRegressionTest {
    @Before public void timedFrames() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
    }

    @Test public void versionStaysAtRequested190AndCode20() {
        assertEquals(20, BuildConfig.VERSION_CODE);
        assertEquals("1.9.0", BuildConfig.VERSION_NAME);
    }

    @Test public void controllerRowsAnimateThenSettleAtTheSameTopInset() {
        for (int count : new int[] {1, 2, 7, 9, 10, 91, 92, 93}) {
            Fixture f = new Fixture(count, 878);
            try {
                assertEquals(8, f.recycler.getPaddingBottom());
                View focused = f.grid.findViewByPosition(0);
                assertNotNull(focused);
                assertTrue(focused.requestFocus());
                int totalRows = (count + 2) / 3;
                int firstRow = 0;
                assertAligned(f, firstRow);
                for (int row = 1; row < totalRows; row++) {
                    View next = f.recycler.focusSearch(focused, View.FOCUS_DOWN);
                    assertNotNull("Next row must remain reachable", next);
                    assertEquals(row * 3, f.recycler.getChildAdapterPosition(next));
                    assertTrue(next.requestFocus());
                    focused = next;
                    int wantedFirstRow = Math.max(0, row - 2);
                    f.frames(wantedFirstRow == firstRow ? 1 : 12);
                    firstRow = wantedFirstRow;
                    assertAligned(f, firstRow);
                    assertTrue(focused.hasFocus());
                }
                assertFalse("No fractional-row end clamp", f.recycler.canScrollVertically(1));
                for (int row = totalRows - 2; row >= 0; row--) {
                    View next = f.recycler.focusSearch(focused, View.FOCUS_UP);
                    assertNotNull(next);
                    assertEquals(row * 3, f.recycler.getChildAdapterPosition(next));
                    assertTrue(next.requestFocus());
                    focused = next;
                    int wantedFirstRow = Math.min(firstRow, row);
                    f.frames(wantedFirstRow == firstRow ? 1 : 12);
                    firstRow = wantedFirstRow;
                    assertAligned(f, firstRow);
                }
                assertFalse(f.recycler.canScrollVertically(-1));
                for (int repeat = 0; repeat < 10; repeat++) {
                    assertSame(focused, f.recycler.focusSearch(focused, View.FOCUS_UP));
                    assertTrue(focused.hasFocus());
                }
            } finally { f.close(); }
        }
    }

    @Test public void controllerOwnedVerticalNavigationNeverChangesColumn() {
        Fixture f = new Fixture(93, 878);
        try {
            for (int column = 0; column < 3; column++) {
                f.grid.cancelPendingControllerFocus();
                f.grid.scrollToPositionWithOffset(0, 0);
                f.frames(2);
                View focused = f.grid.findViewByPosition(column);
                assertNotNull(focused);
                assertTrue(focused.requestFocus());
                int logical = column;
                for (int row = 1; row < 20; row++) {
                    assertTrue(f.grid.moveControllerFocus(f.recycler, logical, 1));
                    logical = row * 3 + column;
                    assertEquals("Pending DOWN target must keep its column", column,
                            f.grid.getPendingControllerFocusPosition() % 3);
                    // Let the 150 ms movement finish. RowAlignedRecyclerView then commits
                    // focus to the exact pending adapter position, not a geometric candidate.
                    f.frames(12);
                    View selected = f.recycler.getFocusedChild();
                    assertNotNull(selected);
                    assertEquals(logical, f.recycler.getChildAdapterPosition(selected));
                    assertEquals("Settled DOWN focus must keep its column", column, logical % 3);
                    assertEquals(RecyclerView.NO_POSITION, f.grid.getPendingControllerFocusPosition());
                }
            }
        } finally { f.close(); }
    }

    @Test public void rapidVerticalRepeatsRetargetPendingPositionWithoutColumnDrift() {
        Fixture f = new Fixture(93, 878);
        try {
            for (int column = 0; column < 3; column++) {
                f.grid.cancelPendingControllerFocus();
                f.grid.scrollToPositionWithOffset(0, 0);
                f.frames(2);
                View focused = f.grid.findViewByPosition(column);
                assertNotNull(focused);
                assertTrue(focused.requestFocus());
                int logical = column;
                for (int row = 1; row <= 8; row++) {
                    assertTrue(f.grid.moveControllerFocus(f.recycler, logical, 1));
                    logical = row * 3 + column;
                    assertEquals(logical, f.grid.getPendingControllerFocusPosition());
                    // Interrupt before the previous movement can finish, like held DPAD.
                    f.frames(2);
                }
                f.frames(12);
                View selected = f.recycler.getFocusedChild();
                assertNotNull(selected);
                assertEquals(logical, f.recycler.getChildAdapterPosition(selected));
                assertEquals(column, logical % 3);
            }
        } finally { f.close(); }
    }

    @Test public void incompleteLastRowDoesNotCauseSidewaysFocusJump() {
        Fixture f = new Fixture(10, 878);
        try {
            View columnThree = f.grid.findViewByPosition(8);
            assertNotNull(columnThree);
            assertTrue(columnThree.requestFocus());
            assertSame(columnThree, f.recycler.focusSearch(columnThree, View.FOCUS_DOWN));
        } finally { f.close(); }
    }

    @Test public void focusZoomOrOversizedRequestedRectDoesNotMoveAVisibleRow() {
        Fixture f = new Fixture(40, 878);
        try {
            for (int position = 0; position < 9; position++) {
                View tile = f.grid.findViewByPosition(position);
                assertNotNull(tile);
                tile.setScaleX(1.1f);
                tile.setScaleY(1.1f);
                assertTrue(tile.requestFocus());
                f.grid.requestChildRectangleOnScreen(f.recycler, tile,
                        new Rect(-50, -50, 490, 298), false, false);
                assertAligned(f, 0);
            }
        } finally { f.close(); }
    }

    @Test public void wheelScrollSettlesWithoutLeavingAPartialTopRow() {
        Fixture f = new Fixture(92, 878);
        try {
            f.recycler.scrollBy(0, 170);
            assertEquals(RecyclerView.SCROLL_STATE_IDLE, f.recycler.getScrollState());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(150, TimeUnit.MILLISECONDS);
            assertAligned(f, 1);
            f.recycler.scrollBy(0, -170);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(150, TimeUnit.MILLISECONDS);
            assertAligned(f, 0);
            f.recycler.scrollBy(0, 100000);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(150, TimeUnit.MILLISECONDS);
            assertAligned(f, 28);
        } finally { f.close(); }
    }

    @Test public void restoredLegacyOffsetSnapsBeforeFirstDraw() {
        Fixture f = new Fixture(92, 878);
        try {
            // Save a genuinely unaligned offset using the old plain RecyclerView.
            RecyclerView old = new RecyclerView(f.owner.get());
            GridLayoutManager oldGrid = new GridLayoutManager(f.owner.get(), 3);
            old.setLayoutManager(oldGrid);
            old.setPadding(0, 6, 0, 8);
            old.addItemDecoration(new SpacingDecoration(40));
            old.setAdapter(new Tiles(92));
            layout(old, 878);
            oldGrid.scrollToPositionWithOffset(18, -73);
            layout(old, 878);
            Parcelable legacy = oldGrid.onSaveInstanceState();
            f.grid.onRestoreInstanceState(legacy);
            f.frames(2);
            assertAligned(f, 6);
            Parcelable aligned = f.grid.onSaveInstanceState();
            f.grid.scrollToPositionWithOffset(0, 0);
            f.frames(2);
            assertAligned(f, 0);
            f.grid.onRestoreInstanceState(aligned);
            f.frames(2);
            assertAligned(f, 6);
        } finally { f.close(); }
    }

    @Test public void actualViewportHeightControlsRemainderAndEndAlignment() {
        for (int height : new int[] {878, 877, 870, 838, 818, 518, 401, 298}) {
            Fixture f = new Fixture(92, height);
            try {
                int expectedRows = (height - 6) / 288;
                assertEquals((height - 6) % 288, f.recycler.getPaddingBottom());
                assertAligned(f, 0);
                f.recycler.scrollBy(0, 100000);
                f.frames(2);
                assertAligned(f, 31 - expectedRows);
                // Resize while scrolled: no stale remainder from nominal display metrics.
                f.height = 878;
                f.recycler.setLayoutParams(new ViewGroup.LayoutParams(1440, 878));
                f.frames(3);
                assertEquals(8, f.recycler.getPaddingBottom());
                assertAligned(f, 28);
            } finally { f.close(); }
        }
    }

    @Test public void emptyCategoryAndTinyWindowDoNotCreateAnEmptyPaddedViewport() {
        Fixture f = new Fixture(0, 270);
        try {
            assertEquals(0, f.recycler.getPaddingBottom());
            assertEquals(0, f.recycler.getChildCount());
        } finally { f.close(); }
    }

    private static void assertAligned(Fixture f, int expectedFirstRow) {
        assertEquals(expectedFirstRow * 3, f.grid.findFirstVisibleItemPosition());
        View first = f.grid.findViewByPosition(expectedFirstRow * 3);
        assertNotNull(first);
        assertEquals(26, first.getTop());
        assertEquals(6, f.grid.getDecoratedTop(first));
        assertEquals(RecyclerView.SCROLL_STATE_IDLE, f.recycler.getScrollState());
        View below = f.grid.findViewByPosition((expectedFirstRow + 1) * 3);
        if (below != null) assertEquals(40, below.getTop() - first.getBottom());
    }

    private static void layout(View recycler, int height) {
        recycler.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 1440, height);
    }

    private static final class Fixture {
        final ActivityController<Activity> owner;
        final RowAlignedRecyclerView recycler;
        final ControllerGridLayoutManager grid;
        int height;
        Fixture(int count, int height) {
            this.height = height;
            owner = Robolectric.buildActivity(Activity.class).setup().visible();
            recycler = new RowAlignedRecyclerView(owner.get());
            grid = new ControllerGridLayoutManager(owner.get(), 3);
            recycler.setLayoutManager(grid);
            recycler.setHasFixedSize(true);
            recycler.setItemAnimator(null);
            recycler.setPreserveFocusAfterLayout(false);
            recycler.setPadding(0, 6, 0, 0);
            recycler.setClipToPadding(false);
            recycler.setClipChildren(false);
            recycler.addItemDecoration(new SpacingDecoration(40));
            recycler.setAdapter(new Tiles(count));
            owner.get().setContentView(recycler, new ViewGroup.LayoutParams(1440, height));
            frames(4);
        }
        void frames(int count) {
            for (int i = 0; i < count; i++) {
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS);
                layout(recycler, height);
            }
        }
        void close() { owner.pause().stop().destroy(); }
    }

    private static final class Tiles extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final int count;
        Tiles(int count) { this.count = count; }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            View view = new View(parent.getContext());
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setLayoutParams(new RecyclerView.LayoutParams(440, 248));
            return new RecyclerView.ViewHolder(view) {};
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {}
        @Override public int getItemCount() { return count; }
    }
}
