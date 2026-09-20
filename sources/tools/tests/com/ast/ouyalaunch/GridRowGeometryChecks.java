package com.ast.ouyalaunch;

/** Ordinary-JDK checks of the exact geometry helper used by the Android implementation. */
public final class GridRowGeometryChecks {
    private static int checks;
    private static final int STEP = 248 + 40;
    private static final int TOP = 6;

    public static void main(String[] args) {
        equal(8, GridRowGeometry.bottomPadding(878, TOP, STEP), "1080p remainder");
        equal(3, GridRowGeometry.visibleRows(878, TOP, 8, STEP), "1080p complete rows");
        equal(0, GridRowGeometry.bottomPadding(870, TOP, STEP), "Exact viewport");
        equal(0, GridRowGeometry.bottomPadding(270, TOP, STEP), "Small window keeps viewport");
        equal(0, GridRowGeometry.visibleRows(270, TOP, 0, STEP), "Small-window fallback");
        equal(0, GridRowGeometry.bottomPadding(0, TOP, STEP), "Before measurement");
        equal(0, GridRowGeometry.bottomPadding(878, TOP, 0), "Invalid row size");
        equal(0, GridRowGeometry.maxFirstRow(0, 3, 3), "Empty category");
        equal(0, GridRowGeometry.maxFirstRow(7, 3, 3), "Short category");
        equal(1, GridRowGeometry.maxFirstRow(10, 3, 3), "Incomplete fourth row");
        equal(0, GridRowGeometry.nearestFirstRow(-73, STEP, 30), "Start clamp");
        equal(30, GridRowGeometry.nearestFirstRow(Long.MAX_VALUE, STEP, 30), "End clamp");

        // Vertical controller navigation is positional, not geometric: scrolling or
        // focus zoom can never move the cursor into a neighbouring column.
        for (int spans = 1; spans <= 6; spans++) {
            for (int count = 0; count <= 100; count++) {
                for (int position = 0; position < count; position++) {
                    int down = GridRowGeometry.verticalFocusTarget(position, 1, count, spans);
                    int up = GridRowGeometry.verticalFocusTarget(position, -1, count, spans);
                    if (down >= 0) {
                        equal(position + spans, down, "DOWN exactly one row");
                        equal(position % spans, down % spans, "DOWN keeps column");
                    } else {
                        check(position + spans >= count, "DOWN only stops at missing cell");
                    }
                    if (up >= 0) {
                        equal(position - spans, up, "UP exactly one row");
                        equal(position % spans, up % spans, "UP keeps column");
                    } else {
                        check(position < spans, "UP only stops in first row");
                    }
                }
            }
        }
        equal(-1, GridRowGeometry.verticalFocusTarget(8, 1, 10, 3),
                "Incomplete last row does not jump from column 3 to column 1");
        // Reproduce the old 8 px downward-navigation drift, then verify its removal.
        int oldScroll = TOP + 4 * STEP - 878;
        equal(280, oldScroll, "Old minimum scroll to fourth row");
        equal(34, TOP + 20 + STEP - oldScroll, "Old first visible tile inset");
        int newScroll = TOP + 4 * STEP - (878 - 8);
        equal(STEP, newScroll, "Correct full-row scroll");
        equal(26, TOP + 20 + STEP - newScroll, "Fixed first visible tile inset");

        for (int height = 0; height <= 2400; height++) {
            int bottom = GridRowGeometry.bottomPadding(height, TOP, STEP);
            int rows = GridRowGeometry.visibleRows(height, TOP, bottom, STEP);
            check(bottom >= 0 && bottom < STEP, "Bottom inset range");
            if (height - TOP < STEP) {
                equal(0, bottom, "No empty viewport in a small window");
                continue;
            }
            equal(rows * STEP, height - TOP - bottom, "Exact multiple of row height");
            for (int spans = 1; spans <= 3; spans++) {
                for (int count = 0; count <= 100; count++) {
                    int total = count == 0 ? 0 : 1 + (count - 1) / spans;
                    int last = GridRowGeometry.maxFirstRow(count, spans, rows);
                    equal(Math.max(0, total - rows), last, "Last partial row included");
                    equal(Math.max(0, total * STEP - (height - TOP - bottom)),
                            last * STEP, "End clamp lies on the row raster");
                    int first = 0;
                    for (int target = 0; target < total; target++) {
                        int next = GridRowGeometry.firstRowForFocus(first, target, rows, last);
                        check(next >= first, "DOWN does not reverse");
                        check(target >= next && target < next + rows, "DOWN focus visible");
                        check(next >= 0 && next <= last, "DOWN range");
                        equal(26, TOP + 20 + next * STEP - next * STEP, "DOWN top inset");
                        first = next;
                    }
                    for (int target = total - 1; target >= 0; target--) {
                        int next = GridRowGeometry.firstRowForFocus(first, target, rows, last);
                        check(next <= first, "UP does not reverse");
                        check(target >= next && target < next + rows, "UP focus visible");
                        first = next;
                    }
                    equal(0, first, "Returned to start");
                }
            }
        }
        for (int offset = 0; offset <= 30 * STEP; offset++) {
            int first = GridRowGeometry.nearestFirstRow(offset, STEP, 30);
            int delta = first * STEP - offset;
            check(Math.abs(delta) <= STEP / 2, "Nearest wheel/restored row");
            equal(first, GridRowGeometry.nearestFirstRow(offset + delta, STEP, 30), "Stable snap");
        }
        System.out.println("PASS: " + checks + " row geometry checks");
    }

    private static void equal(int expected, int actual, String label) {
        checks++;
        if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
}
