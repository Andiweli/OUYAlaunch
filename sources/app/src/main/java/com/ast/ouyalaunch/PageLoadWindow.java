package com.ast.ouyalaunch;

/** The final visible rows, not a speculative extra screen of icons. No Android dependencies. */
final class PageLoadWindow {
    final int first;
    final int count;

    private PageLoadWindow(int first, int count) {
        this.first = first;
        this.count = count;
    }

    static PageLoadWindow forViewport(int itemCount, int spans, int savedFirst,
            int focusPosition, int viewportHeight, int topPadding, int rowHeight) {
        if (itemCount <= 0) return new PageLoadWindow(0, 0);
        spans = Math.max(1, spans);
        int bottom = GridRowGeometry.bottomPadding(viewportHeight, topPadding, rowHeight);
        int rows = Math.max(1, GridRowGeometry.visibleRows(viewportHeight,
                topPadding, bottom, rowHeight));
        int maxFirst = GridRowGeometry.maxFirstRow(itemCount, spans, rows);
        int firstRow = Math.max(0, Math.min(maxFirst, Math.max(0, savedFirst) / spans));
        // Focus restoration can scroll the saved viewport after apps move/disappear.
        // Prepare that final window too, without decoding the intervening rows.
        if (focusPosition >= 0 && focusPosition < itemCount) {
            firstRow = GridRowGeometry.firstRowForFocus(firstRow, focusPosition / spans,
                    rows, maxFirst);
        }
        int first = firstRow * spans;
        int count = (int) Math.min((long) itemCount - first, (long) rows * spans);
        return new PageLoadWindow(first, count);
    }
}
