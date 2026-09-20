package com.ast.ouyalaunch;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** Keep controller focus and the viewport on the same, unscaled tile-row raster. */
final class ControllerGridLayoutManager extends GridLayoutManager {
    // Keep row changes quick enough for held-controller navigation while removing the
    // hard one-frame jump. The row still lands on the exact existing raster.
    private static final int CONTROLLER_ROW_SCROLL_MS = 150;
    private static final Interpolator CONTROLLER_ROW_INTERPOLATOR =
            new DecelerateInterpolator(1.35f);
    private int pendingControllerFocusPosition = RecyclerView.NO_POSITION;

    ControllerGridLayoutManager(Context context, int spans) { super(context, spans); }

    int getPendingControllerFocusPosition() { return pendingControllerFocusPosition; }

    void cancelPendingControllerFocus() {
        pendingControllerFocusPosition = RecyclerView.NO_POSITION;
    }

    /**
     * Controller-owned vertical navigation. The logical destination is positional
     * (same column, next/previous row), never chosen from transient screen geometry.
     * While a row is animating, repeated presses advance from the pending destination
     * so a held DPAD remains deterministic.
     */
    boolean moveControllerFocus(RecyclerView parent, int focusedPosition, int rowDelta) {
        int itemCount = getItemCount();
        int anchor = pendingControllerFocusPosition;
        if (anchor == RecyclerView.NO_POSITION || anchor < 0 || anchor >= itemCount) {
            anchor = focusedPosition;
        }
        int targetPosition = GridRowGeometry.verticalFocusTarget(anchor, rowDelta,
                itemCount, getSpanCount());
        if (targetPosition == RecyclerView.NO_POSITION) return false;

        // Publish the new logical target before stopScroll(). RowAlignedRecyclerView
        // posts its idle settle callback; by the time it runs it must see this latest
        // target rather than an obsolete one from the interrupted animation.
        pendingControllerFocusPosition = targetPosition;
        parent.stopScroll();

        View first = findViewByPosition(findFirstVisibleItemPosition());
        if (first == null || getPosition(first) == RecyclerView.NO_POSITION) {
            parent.smoothScrollToPosition(targetPosition);
            return true;
        }

        int rowHeight = rowHeight(first);
        int rows = GridRowGeometry.visibleRows(getHeight(), getPaddingTop(),
                getPaddingBottom(), rowHeight);
        if (rows == 0) {
            parent.smoothScrollToPosition(targetPosition);
            return true;
        }

        long offset = scrollOffset(first, rowHeight);
        int lastFirstRow = GridRowGeometry.maxFirstRow(itemCount, getSpanCount(), rows);
        int firstRow = GridRowGeometry.nearestFirstRow(offset, rowHeight, lastFirstRow);
        int wantedFirstRow = GridRowGeometry.firstRowForFocus(firstRow,
                targetPosition / getSpanCount(), rows, lastFirstRow);
        int dy = scrollDelta(wantedFirstRow, rowHeight, offset);

        if (dy == 0) {
            // Inside the current viewport the exact tile is already attached, so focus
            // can move immediately without involving Android's FocusFinder at all.
            if (!finishPendingControllerFocus(parent)) {
                parent.postOnAnimation(() -> finishPendingControllerFocus(parent));
            }
        } else {
            parent.smoothScrollBy(0, dy, CONTROLLER_ROW_INTERPOLATOR,
                    CONTROLLER_ROW_SCROLL_MS);
        }
        return true;
    }

    /** Request the exact pending tile after the row raster has settled. */
    boolean finishPendingControllerFocus(RecyclerView parent) {
        int position = pendingControllerFocusPosition;
        if (position == RecyclerView.NO_POSITION) return false;
        if (position < 0 || position >= getItemCount()) {
            pendingControllerFocusPosition = RecyclerView.NO_POSITION;
            return false;
        }
        View target = findViewByPosition(position);
        if (target == null || target.getParent() != parent || !target.isFocusable()) return false;
        pendingControllerFocusPosition = RecyclerView.NO_POSITION;
        return target.requestFocus();
    }

    @Override public View onInterceptFocusSearch(View focused, int direction) {
        if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) {
            int position = getPosition(focused);
            if (position != RecyclerView.NO_POSITION) {
                int rowDelta = direction == View.FOCUS_UP ? -1 : 1;
                int targetPosition = GridRowGeometry.verticalFocusTarget(position, rowDelta,
                        getItemCount(), getSpanCount());
                // At the first/last available cell, retain focus instead of letting
                // FocusFinder choose a tile in a neighbouring column.
                if (targetPosition == RecyclerView.NO_POSITION) return focused;
                View target = findViewByPosition(targetPosition);
                if (target != null && target.isFocusable()) return target;
            }
        }
        return super.onInterceptFocusSearch(focused, direction);
    }

    @Override public View onFocusSearchFailed(View focused, int direction,
            RecyclerView.Recycler recycler, RecyclerView.State state) {
        if (direction != View.FOCUS_UP && direction != View.FOCUS_DOWN)
            return super.onFocusSearchFailed(focused, direction, recycler, state);

        int position = getPosition(focused);
        if (position == RecyclerView.NO_POSITION)
            return super.onFocusSearchFailed(focused, direction, recycler, state);
        int rowDelta = direction == View.FOCUS_UP ? -1 : 1;
        int targetPosition = GridRowGeometry.verticalFocusTarget(position, rowDelta,
                state.getItemCount(), getSpanCount());
        if (targetPosition == RecyclerView.NO_POSITION) return focused;

        // Let GridLayoutManager lay out the next off-screen row when necessary, but
        // discard its geometry-based candidate. During an animated row scroll those
        // bounds are transient and can otherwise make focus drift diagonally.
        super.onFocusSearchFailed(focused, direction, recycler, state);
        View exact = findViewByPosition(targetPosition);
        return exact != null && exact.isFocusable() ? exact : focused;
    }

    @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child,
            Rect rect, boolean immediate) {
        return requestChildRectangleOnScreen(parent, child, rect, immediate, false);
    }

    @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child,
            Rect rect, boolean immediate, boolean focusedChildVisible) {
        if (parent.isComputingLayout() || parent.hasPendingAdapterUpdates()) return false;
        // Never accumulate smooth-scroll corrections during held DPAD input.
        parent.stopScroll();
        int position = getPosition(child);
        if (position == RecyclerView.NO_POSITION || child.getParent() != parent) return false;
        int rowHeight = rowHeight(child);
        int rows = GridRowGeometry.visibleRows(getHeight(), getPaddingTop(),
                getPaddingBottom(), rowHeight);
        if (rows == 0) {
            // A resized window smaller than one tile still needs ordinary pixel scrolling.
            return super.requestChildRectangleOnScreen(parent, child, rect, true, focusedChildVisible);
        }

        // Use layout/decorated coordinates, NEVER the animated focus rectangle. Each
        // row includes both halves of the gap, even the final, partly populated row.
        long offset = scrollOffset(child, rowHeight);
        int lastFirstRow = GridRowGeometry.maxFirstRow(getItemCount(), getSpanCount(), rows);
        int firstRow = GridRowGeometry.nearestFirstRow(offset, rowHeight, lastFirstRow);
        firstRow = GridRowGeometry.firstRowForFocus(firstRow, position / getSpanCount(),
                rows, lastFirstRow);
        int dy = scrollDelta(firstRow, rowHeight, offset);
        if (dy == 0) return false;
        if (focusedChildVisible) {
            View focused = getFocusedChild();
            // Retain RecyclerView's unfocusable-child contract: an existing focus must
            // not be scrolled completely outside the padded viewport.
            if (focused == null || rowBottom(focused) - (long) dy <= getPaddingTop()
                    || rowTop(focused) - (long) dy >= getHeight() - getPaddingBottom()) return false;
        }
        // Focus changes are row-sized. Animate that exact delta instead of jumping it,
        // then RowAlignedRecyclerView snaps any rounding/clamping remainder when the
        // RecyclerView returns to IDLE. stopScroll() above makes rapid DPAD repeats
        // retarget cleanly from the current in-between position. Honor immediate
        // layout/restoration requests so only normal on-screen controller navigation
        // receives the motion effect.
        if (immediate) parent.scrollBy(0, dy);
        else parent.smoothScrollBy(0, dy, CONTROLLER_ROW_INTERPOLATOR, CONTROLLER_ROW_SCROLL_MS);
        return true;
    }

    /** Settle touch/wheel scrolling and restored old offsets at the nearest complete row. */
    void snapToRow(RecyclerView parent) {
        if (parent.isComputingLayout() || parent.hasPendingAdapterUpdates()
                || parent.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)
            return;
        View first = findViewByPosition(findFirstVisibleItemPosition());
        if (first == null || getPosition(first) == RecyclerView.NO_POSITION) return;
        int rowHeight = rowHeight(first);
        int rows = GridRowGeometry.visibleRows(getHeight(), getPaddingTop(),
                getPaddingBottom(), rowHeight);
        if (rows == 0) return;
        long offset = scrollOffset(first, rowHeight);
        int lastFirstRow = GridRowGeometry.maxFirstRow(getItemCount(), getSpanCount(), rows);
        int firstRow = GridRowGeometry.nearestFirstRow(offset, rowHeight, lastFirstRow);
        int dy = scrollDelta(firstRow, rowHeight, offset);
        if (dy != 0) parent.scrollBy(0, dy);
    }

    private long scrollOffset(View child, int rowHeight) {
        return (long) (getPosition(child) / getSpanCount()) * rowHeight
                + getPaddingTop() - rowTop(child);
    }

    private int rowHeight(View child) {
        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) child.getLayoutParams();
        return getDecoratedMeasuredHeight(child) + params.topMargin + params.bottomMargin;
    }

    private int rowTop(View child) {
        return getDecoratedTop(child)
                - ((RecyclerView.LayoutParams) child.getLayoutParams()).topMargin;
    }

    private int rowBottom(View child) {
        return getDecoratedBottom(child)
                + ((RecyclerView.LayoutParams) child.getLayoutParams()).bottomMargin;
    }

    private static int scrollDelta(int firstRow, int rowHeight, long offset) {
        long delta = (long) firstRow * rowHeight - offset;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, delta));
    }

    @Override public boolean supportsPredictiveItemAnimations() { return false; }
}
