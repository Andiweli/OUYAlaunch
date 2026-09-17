package com.ast.ouyalaunch;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 28})
public class NavigationRegressionTest {
    @Test public void repeatedDownwardFocusRequestsNeverReverseOrQueueSmoothScrolls() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TrackingRecycler recycler = new TrackingRecycler(activity);
        ControllerGridLayoutManager grid = new ControllerGridLayoutManager(activity, 3);
        recycler.setLayoutManager(grid);
        recycler.setItemAnimator(null);
        recycler.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                View tile = new View(parent.getContext());
                tile.setFocusable(true);
                tile.setLayoutParams(new RecyclerView.LayoutParams(200, 180));
                return new RecyclerView.ViewHolder(tile) {};
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {}
            @Override public int getItemCount() { return 90; }
        });
        activity.setContentView(recycler);
        layout(recycler);
        // Every first-row column must retain focus, even with repeated UP requests.
        for (int column = 0; column < grid.getSpanCount(); column++) {
            View tile = grid.findViewByPosition(column);
            tile.requestFocus();
            for (int repeat = 0; repeat < 8; repeat++) {
                assertSame(tile, recycler.focusSearch(tile, View.FOCUS_UP));
                assertTrue(tile.hasFocus());
            }
        }
        View secondRow = grid.findViewByPosition(3);
        secondRow.requestFocus();
        assertSame(grid.findViewByPosition(0), recycler.focusSearch(secondRow, View.FOCUS_UP));
        View focused = grid.findViewByPosition(0);
        focused.requestFocus();
        int previous = 0;
        for (int row = 1; row < 25; row++) {
            View target = recycler.focusSearch(focused, View.FOCUS_DOWN);
            // Reveal the next attached row; immediate focus scrolling attaches further rows.
            assertNotNull("Next row must be available", target);
            grid.requestChildRectangleOnScreen(recycler, target, new Rect(0, 0, 200, 180), false, false);
            target.requestFocus();
            assertEquals(row * 3, recycler.getChildAdapterPosition(target));
            focused = target;
            layout(recycler);
            int offset = recycler.computeVerticalScrollOffset();
            assertTrue("Downward navigation must not scroll upward", offset >= previous);
            previous = offset;
        }
        assertTrue(previous > 0);
        assertEquals(0, recycler.smoothRequests);
        activity.finish();
    }
    private static void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(450, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 600, 450);
    }
    private static class TrackingRecycler extends RecyclerView {
        int smoothRequests;
        TrackingRecycler(Activity activity) { super(activity); }
        @Override public void smoothScrollBy(int x, int y) { smoothRequests++; super.smoothScrollBy(x, y); }
    }
    @Test public void checkboxHighlightUsesOrangeForHoverAndControllerFocus() {
        int orange = 0xffff8a00;
        assertEquals(orange, CategoryDialog.checkboxColors().getColorForState(
                new int[] {android.R.attr.state_enabled, android.R.attr.state_hovered}, 0));
        assertEquals(orange, CategoryDialog.checkboxColors().getColorForState(
                new int[] {android.R.attr.state_enabled, android.R.attr.state_focused}, 0));
    }
}
