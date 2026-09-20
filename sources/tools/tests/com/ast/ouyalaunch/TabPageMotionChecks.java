package com.ast.ouyalaunch;

/** Executable without Android SDK; exercises the exact production entrance geometry. */
public final class TabPageMotionChecks {
    private static int assertions;

    public static void main(String[] args) {
        check(300L == TabPageMotion.DURATION_MS, "Existing 300 ms fade duration is preserved");
        equal(44f, offset(0, -1, 1, false, 440), "Right enters from right");
        equal(-44f, offset(1, -1, 0, false, 440), "Left enters from left");
        equal(44f, offset(0, -1, 6, false, 440), "Distant click uses the same short travel");
        equal(-44f, offset(6, -1, 0, false, 440), "Distant reverse click");
        equal(-44f, offset(0, 2, 1, false, 440), "Right/right/left uses latest requested tab");
        equal(44f, offset(6, 3, 4, false, 440), "Left/left/right uses latest requested tab");
        equal(0f, offset(2, 4, 4, false, 440), "Same pending destination has no new direction");
        equal(0f, offset(2, -1, 2, false, 440), "Same current tab has no motion");
        equal(0f, offset(0, 5, 3, true, 440), "Initial/rescan presentation has no swipe");
        equal(0f, offset(0, -1, 1, false, 0), "Zero tile width");
        equal(0f, offset(0, -1, 1, false, -1), "Invalid tile width");
        equal(22f, offset(0, -1, 1, false, 220), "Distance follows actual tile width");

        for (int current = 0; current < 7; current++) {
            for (int pending = -1; pending < 7; pending++) {
                for (int target = 0; target < 7; target++) {
                    int source = pending < 0 ? current : pending;
                    float travel = offset(current, pending, target, false, 440);
                    equal(target == source ? 0f : target > source ? 44f : -44f,
                            travel, "All tab/pending combinations");
                    equal(0f, offset(current, pending, target, true, 440), "All initial states");
                }
            }
        }
        System.out.println("TabPageMotionChecks: " + assertions + " assertions passed.");
    }

    private static float offset(int current, int pending, int target, boolean initial, int width) {
        return TabPageMotion.startTranslationX(current, pending, target, initial, width);
    }

    private static void equal(float expected, float actual, String message) {
        check(Math.abs(expected - actual) < 0.001f,
                message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
