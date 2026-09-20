package com.ast.ouyalaunch;

/** Host-only sizing calculations; these are not Android layout or rendering tests. */
public final class CategoryDialogSizingChecks {
    private static int scenarios;

    public static void main(String[] args) {
        checkDisplay("1080p mdpi", 1920, 1080, 1f);
        checkDisplay("1080p xhdpi", 1920, 1080, 2f);
        checkDisplay("1080p xxxhdpi", 1920, 1080, 4f);
        checkDisplay("720p mdpi", 1280, 720, 1f);
        checkDisplay("720p xhdpi", 1280, 720, 2f);
        checkDisplay("480p hdpi", 800, 480, 1.5f);
        checkDisplay("4K xhdpi", 3840, 2160, 2f);
        checkDisplay("portrait", 720, 1280, 2f);
        checkDisplay("small portrait", 320, 480, 1f);

        CategoryDialogSizing mdpi = CategoryDialogSizing.forDisplay(1920, 1080, 1f, 1f);
        eq(420, mdpi.windowWidth, "mdpi preferred width");
        eq(38, mdpi.px(CategoryDialogSizing.ROW_MIN_HEIGHT_DP), "compact row minimum");
        eq(22, mdpi.px(CategoryDialogSizing.CHECKBOX_SIZE_DP), "checkbox graphic size");
        eq(14f, mdpi.textPx(CategoryDialogSizing.ROW_TEXT_SP), "compact row font");
        eq(0, mdpi.px(0), "zero padding stays zero");
        scenarios++;

        CategoryDialogSizing dense = CategoryDialogSizing.forDisplay(1920, 1080, 4f, 4f);
        CategoryDialogSizing normal = CategoryDialogSizing.forDisplay(1920, 1080, 2f, 2f);
        eq(normal.windowWidth, dense.windowWidth, "screen height must cap dense dialog growth");
        scenarios++;

        CategoryDialogSizing largeFont = CategoryDialogSizing.forDisplay(1920, 1080, 2f, 3f);
        eq(normal.windowWidth, largeFont.windowWidth, "font preference must not widen the popup");
        eq(normal.textPx(14) * 1.5f, largeFont.textPx(14), "font preference remains effective");
        scenarios++;

        CategoryDialogSizing narrow = CategoryDialogSizing.forDisplay(320, 1080, 1f, 1f);
        eq(14f, narrow.textPx(14), "narrow width must not shrink text");
        scenarios++;

        invalid(0, 1080, 1f, 1f);
        invalid(1920, 0, 1f, 1f);
        invalid(1920, 1080, 0f, 1f);
        invalid(1920, 1080, Float.NaN, 1f);
        invalid(1920, 1080, 1f, Float.POSITIVE_INFINITY);
        System.out.println("CategoryDialogSizingChecks: " + scenarios + " scenarios passed (host sizing only)");
    }

    private static void checkDisplay(String label, int width, int height, float density) {
        CategoryDialogSizing s = CategoryDialogSizing.forDisplay(width, height, density, density);
        require(s.windowWidth <= Math.round(width * .84f), label + ": width limit");
        require(s.unit <= density, label + ": never enlarge logical dp units");
        require(s.px(460) <= Math.round(height * .82f), label + ": height budget");
        require(s.windowWidth <= Math.round(420 * density), label + ": preferred width");
        int rows = 7 * s.px(CategoryDialogSizing.ROW_MIN_HEIGHT_DP);
        require(rows < height * .51f, label + ": all seven base rows leave room for dialog chrome");
        require(s.px(22) + s.px(6) <= s.px(38), label + ": checkbox fits the row");
        int nameWidth = s.windowWidth - s.px(32) - s.px(128);
        require(nameWidth > s.textPx(14) * 5, label + ": usable name-field width");
        if (s.windowWidth == s.px(420)) {
            require(nameWidth > s.windowWidth / 2,
                    label + ": normal-width name fields retain the majority of the width");
        }
        System.out.printf(java.util.Locale.ROOT, "%s: width=%d px, row=%d px, text=%.2f px%n",
                label, s.windowWidth, s.px(38), s.textPx(14));
        scenarios++;
    }

    private static void invalid(int width, int height, float density, float scaledDensity) {
        try {
            CategoryDialogSizing.forDisplay(width, height, density, scaledDensity);
            throw new AssertionError("Expected invalid metrics to be rejected");
        } catch (IllegalArgumentException expected) { scenarios++; }
    }

    private static void eq(int expected, int actual, String label) {
        require(expected == actual, label + ": expected " + expected + ", got " + actual);
    }
    private static void eq(float expected, float actual, String label) {
        require(Math.abs(expected - actual) < .0001f, label);
    }
    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
