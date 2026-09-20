package com.ast.ouyalaunch;

/** Integer-pixel row geometry, shared by viewport sizing and controller scrolling. */
final class GridRowGeometry {
    private GridRowGeometry() {}

    static int bottomPadding(int viewportHeight, int topPadding, int rowHeight) {
        if (rowHeight <= 0) return 0;
        int available = Math.max(0, viewportHeight - topPadding);
        // Do not turn a window smaller than one tile into a zero-height viewport.
        return available < rowHeight ? 0 : available % rowHeight;
    }

    static int visibleRows(int viewportHeight, int topPadding, int bottomPadding,
            int rowHeight) {
        if (rowHeight <= 0) return 0;
        return Math.max(0, viewportHeight - topPadding - bottomPadding) / rowHeight;
    }

    static int maxFirstRow(int itemCount, int spans, int visibleRows) {
        if (itemCount <= 0 || spans <= 0 || visibleRows <= 0) return 0;
        int totalRows = 1 + (itemCount - 1) / spans;
        return Math.max(0, totalRows - visibleRows);
    }

    static int nearestFirstRow(long scrollOffset, int rowHeight, int maxFirstRow) {
        if (rowHeight <= 0 || maxFirstRow <= 0) return 0;
        long lastOffset = (long) maxFirstRow * rowHeight;
        long bounded = Math.max(0L, Math.min(lastOffset, scrollOffset));
        return (int) ((bounded + rowHeight / 2) / rowHeight);
    }

    static int firstRowForFocus(int firstRow, int targetRow, int visibleRows,
            int maxFirstRow) {
        if (visibleRows <= 0) return 0;
        int result = firstRow;
        if (targetRow < result) result = targetRow;
        else if (targetRow >= (long) result + visibleRows) result = targetRow - visibleRows + 1;
        return Math.max(0, Math.min(maxFirstRow, result));
    }

    /**
     * Return the item exactly one row above/below while preserving the current column.
     * A missing cell in an incomplete edge row deliberately has no target instead of
     * allowing Android's geometric focus search to jump sideways.
     */
    static int verticalFocusTarget(int position, int rowDelta, int itemCount, int spans) {
        if (position < 0 || position >= itemCount || spans <= 0 || rowDelta == 0) return -1;
        long target = (long) position + (long) rowDelta * spans;
        return target >= 0 && target < itemCount ? (int) target : -1;
    }
}
