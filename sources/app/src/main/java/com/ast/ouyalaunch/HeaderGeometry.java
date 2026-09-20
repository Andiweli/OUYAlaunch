package com.ast.ouyalaunch;

/** Pixel geometry for a full left-to-right grid row, even when the page has no tiles. */
final class HeaderGeometry {
    private HeaderGeometry() {}

    static int rowWidth(int contentWidth, int columns, int tileWidth) {
        if (contentWidth < 0 || columns < 1 || tileWidth < 1) {
            throw new IllegalArgumentException("Invalid grid geometry");
        }
        // GridLayoutManager gives the last span ceil(contentWidth / columns) pixels.
        // The last tile starts one such span before the content area's right edge.
        // Do not include its trailing decoration/unused span space in the tab strip.
        int lastSpanWidth = contentWidth / columns;
        if (contentWidth % columns != 0) lastSpanWidth++;
        return contentWidth - lastSpanWidth + tileWidth;
    }
}
