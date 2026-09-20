package com.ast.ouyalaunch;

/** Pure entrance geometry, shared by tab navigation and SDK-independent regression checks. */
final class TabPageMotion {
    // Preserve the existing fade duration/easing and add only a small horizontal movement.
    static final long DURATION_MS = 300L;
    private static final float TILE_WIDTH_FRACTION = 0.10f;

    private TabPageMotion() {}

    static float startTranslationX(int currentTab, int requestedTab, int targetTab,
            boolean initial, int tileWidthPx) {
        if (initial || tileWidthPx <= 0) return 0f;
        int from = requestedTab >= 0 ? requestedTab : currentTab;
        if (targetTab == from) return 0f;
        // Fixed-pixel tiles use a fixed-pixel slide: 440 px * 10% = 44 px.
        // Skipping disabled tabs or clicking a distant tab does not increase the travel.
        float distance = tileWidthPx * TILE_WIDTH_FRACTION;
        return targetTab > from ? distance : -distance;
    }
}
