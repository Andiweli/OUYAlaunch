package com.ast.ouyalaunch;

/** Size the compact editor independently of the launcher's fixed-pixel tile grid. */
final class CategoryDialogSizing {
    static final int WIDTH_DP = 420;
    static final int ROW_MIN_HEIGHT_DP = 38;
    static final int ROW_TEXT_SP = 14;
    static final int CHECKBOX_SIZE_DP = 22;
    static final int LABEL_WIDTH_DP = 128;
    // Leave room for all seven rows, the title/hint, buttons and dialog decorations.
    // This is a sizing budget, NOT a fixed window height. The window wraps its content.
    private static final int HEIGHT_BUDGET_DP = 460;
    private static final float MAX_WIDTH_FRACTION = .84f;
    private static final float MAX_HEIGHT_FRACTION = .82f;

    final float unit;
    final float fontScale;
    final int windowWidth;

    private CategoryDialogSizing(int screenWidth, float unit, float fontScale) {
        this.unit = unit;
        this.fontScale = fontScale;
        windowWidth = Math.min(px(WIDTH_DP), Math.round(screenWidth * MAX_WIDTH_FRACTION));
    }

    static CategoryDialogSizing forDisplay(int width, int height, float density, float scaledDensity) {
        if (width <= 0 || height <= 0 || !positive(density) || !positive(scaledDensity)) {
            throw new IllegalArgumentException("Invalid editor display metrics");
        }
        // Dense handheld screens must not turn a compact dp dialog into a full-screen
        // window with only five visible rows. Do not rescale again when the IME opens:
        // its smaller viewport should scroll, not make the text and buttons shrink.
        float unit = Math.min(density, height * MAX_HEIGHT_FRACTION / HEIGHT_BUDGET_DP);
        // Narrow screens constrain the window width, not the font size. The hint can
        // wrap and the single-line name fields can scroll horizontally if necessary.
        return new CategoryDialogSizing(width, unit, scaledDensity / density);
    }

    int px(int value) {
        return value == 0 ? 0 : Math.max(1, Math.round(value * unit));
    }

    float textPx(int sp) {
        // Keep the user's font-size preference. Oversized text may require scrolling,
        // but font-aware controls must never cut glyphs off to meet a fixed row height.
        return sp * unit * fontScale;
    }

    private static boolean positive(float value) {
        return value > 0 && !Float.isInfinite(value) && !Float.isNaN(value);
    }
}
