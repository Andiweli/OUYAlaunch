package com.ast.ouyalaunch;

import android.content.Context;
import android.util.AttributeSet;
import androidx.recyclerview.widget.RecyclerView;

/** A fixed-tile viewport whose scrollable height is an exact number of tile rows. */
public final class RowAlignedRecyclerView extends RecyclerView {
    private final int rowHeight;
    private boolean snapping;
    private final Runnable settleRows = this::snapRows;

    public RowAlignedRecyclerView(Context context) { this(context, null); }
    public RowAlignedRecyclerView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public RowAlignedRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        rowHeight = getResources().getDimensionPixelSize(R.dimen.tile_height_px)
                + getResources().getDimensionPixelSize(R.dimen.grid_spacing_px);
        addOnScrollListener(new OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView view, int state) {
                removeCallbacks(settleRows);
                if (state == SCROLL_STATE_IDLE) postOnAnimation(settleRows);
            }

            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                // Mouse-wheel/programmatic scrollBy can stay IDLE throughout. Debounce
                // those events, but never snap in the middle of a drag or a fling.
                if (dy != 0 && !snapping && getScrollState() == SCROLL_STATE_IDLE) {
                    removeCallbacks(settleRows);
                    postDelayed(settleRows, 100L);
                }
            }
        });
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        // The XML gives this view an exact, weighted height. Account for any system
        // bars using that real measured height, not nominal 1080p/display metrics.
        // At 1080p: 878 - 6 - 8 = 864 = 3 * (248 + 40).
        // Keeping the remainder at the BOTTOM preserves the 26 px top tile inset.
        int bottom = GridRowGeometry.bottomPadding(getMeasuredHeight(), getPaddingTop(), rowHeight);
        if (getPaddingBottom() != bottom) {
            setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), bottom);
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // LayoutManager state from older versions may contain a fractional-row offset.
        // Correct it before drawing, including after a tab restore or height change.
        // snapRows ignores DRAGGING/SETTLING so touch/fling gestures remain continuous.
        snapRows();
    }

    private void snapRows() {
        if (snapping || isComputingLayout() || getScrollState() != SCROLL_STATE_IDLE) return;
        LayoutManager layout = getLayoutManager();
        if (!(layout instanceof ControllerGridLayoutManager)) return;
        snapping = true;
        try {
            ControllerGridLayoutManager grid = (ControllerGridLayoutManager) layout;
            grid.snapToRow(this);
            // Vertical controller focus is committed only after the animated row has
            // landed. This prevents Android FocusFinder from choosing a centre-column
            // child from transient bounds while the content is moving.
            grid.finishPendingControllerFocus(this);
        } finally {
            snapping = false;
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(settleRows);
    }
}
