package com.ast.ouyalaunch;

/** Run with an ordinary JDK; no Android SDK or test-library download is required. */
public final class HeaderGeometryChecks {
    private static int checks;

    public static void main(String[] args) {
        equal(1400, HeaderGeometry.rowWidth(1440, 3, 440), "Three-column standard grid");
        equal(1560, HeaderGeometry.rowWidth(1680, 3, 440), "API 16 without relative margins");
        equal(1436, HeaderGeometry.rowWidth(1494, 3, 440), "Pixel-centered three-column grid");
        equal(938, HeaderGeometry.rowWidth(996, 2, 440), "Two-column narrow-screen fallback");
        equal(440, HeaderGeometry.rowWidth(498, 1, 440), "One-column fallback");
        equal(1436, HeaderGeometry.rowWidth(1495, 3, 440), "One remainder pixel");
        equal(1437, HeaderGeometry.rowWidth(1496, 3, 440), "Two remainder pixels");
        equal(440, HeaderGeometry.rowWidth(0, 1, 440), "Unmeasured one-column fallback");
        invalid(-1, 3, 440);
        invalid(1440, 0, 440);
        invalid(1440, 3, 0);

        // Compare the closed-form result with span-by-span pixel distribution.
        // Include all remainder combinations, not just even 1080p widths.
        for (int columns = 1; columns <= 8; columns++) {
            for (int width = 0; width <= 8192; width++) {
                int base = width / columns;
                int remainder = width % columns;
                int carry = 0;
                int lastStart = 0;
                for (int column = 0; column < columns - 1; column++) {
                    carry += remainder;
                    int spanWidth = base;
                    if (carry > 0 && columns - carry < remainder) {
                        spanWidth++;
                        carry -= columns;
                    }
                    lastStart += spanWidth;
                }
                equal(lastStart + 440, HeaderGeometry.rowWidth(width, columns, 440),
                        "Span distribution: width=" + width + ", columns=" + columns);
            }
        }
        System.out.println("PASS: " + checks + " header geometry checks");
    }

    private static void equal(int expected, int actual, String label) {
        checks++;
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void invalid(int width, int columns, int tileWidth) {
        checks++;
        try {
            HeaderGeometry.rowWidth(width, columns, tileWidth);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid geometry was accepted");
    }
}
