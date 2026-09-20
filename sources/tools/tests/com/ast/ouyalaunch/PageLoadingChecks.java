package com.ast.ouyalaunch;

/** Android-independent window and PNG-header checks. */
public final class PageLoadingChecks {
    private static long checks;
    public static void main(String[] args) {
        window(40, 3, 0, -1, 878, 0, 9);
        window(40, 3, 9, -1, 878, 9, 9);
        window(10, 3, 9, -1, 878, 3, 7);
        window(10, 3, 9, 0, 878, 0, 9);
        window(40, 3, 0, 14, 878, 6, 9);
        window(40, 3, 0, -1, 854, 0, 6); // Actual viewport, not nominal display resolution.
        window(40, 3, 0, -1, 518, 0, 3);
        window(40, 3, 0, -1, 1958, 0, 18);
        window(0, 3, 999, 999, 878, 0, 0);
        window(2, 3, 999, -1, 878, 0, 2);
        window(40, 3, 0, -1, 200, 0, 3); // Sub-row resized window stays usable.
        for (int n = 1; n <= 200; n++) for (int spans = 1; spans <= 4; spans++) {
            for (int height : new int[] {100, 294, 518, 854, 878, 1166, 1958}) {
                int rows = Math.max(1, (height - 6) / 288);
                for (int first : new int[] {-1, 0, n / 2, n - 1, n + 20}) {
                    for (int focus : new int[] {-1, 0, n / 3, n - 1, n + 20}) {
                        PageLoadWindow w = PageLoadWindow.forViewport(n, spans, first, focus, height, 6, 288);
                        check(w.first >= 0 && w.first < n, "first index");
                        check(w.first % spans == 0, "row alignment");
                        check(w.count > 0 && w.first + w.count <= n, "bounded count");
                        check(w.count == Math.min(n - w.first, rows * spans), "visible window only");
                        if (focus >= 0 && focus < n) check(focus >= w.first && focus < w.first + w.count,
                                "focus stays in prepared rows");
                    }
                }
            }
        }
        byte[] header = header(484, 273);
        check(PreviewPngHeader.fits(header, 484, 273), "maximum preview");
        check(PreviewPngHeader.fits(header(1, 1), 484, 273), "minimum preview");
        for (int w : new int[] {0, 485, 1000, Integer.MAX_VALUE, Integer.MIN_VALUE, -1})
            check(!PreviewPngHeader.fits(header(w, 273), 484, 273), "invalid/large width");
        for (int h : new int[] {0, 274, 1000, Integer.MAX_VALUE, Integer.MIN_VALUE, -1})
            check(!PreviewPngHeader.fits(header(484, h), 484, 273), "invalid/large height");
        for (int pos = 0; pos < 16; pos++) {
            byte[] invalid = header.clone(); invalid[pos] ^= 1;
            check(!PreviewPngHeader.fits(invalid, 484, 273), "signature and IHDR gate");
        }
        for (int len = 0; len < 24; len++) check(!PreviewPngHeader.fits(new byte[len], 484, 273), "truncated header");
        check(!PreviewPngHeader.fits(null, 484, 273), "null header");
        System.out.println("PageLoadingChecks: " + checks + " checks passed");
    }
    private static void window(int n, int spans, int saved, int focus, int height, int first, int count) {
        PageLoadWindow w = PageLoadWindow.forViewport(n, spans, saved, focus, height, 6, 288);
        check(w.first == first && w.count == count, "expected window " + first + "/" + count + " got " + w.first + "/" + w.count);
    }
    private static byte[] header(int width, int height) {
        byte[] h = {(byte)137,80,78,71,13,10,26,10,0,0,0,13,'I','H','D','R',0,0,0,0,0,0,0,0};
        for (int i = 0; i < 4; i++) { h[16 + i] = (byte)(width >>> (24 - 8*i)); h[20 + i] = (byte)(height >>> (24 - 8*i)); }
        return h;
    }
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
