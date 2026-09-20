package com.ast.ouyalaunch;

/** Cheap size gate for our on-disk PNG previews; the bitmap decoder validates the image. */
final class PreviewPngHeader {
    static final int LENGTH = 24;
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    static boolean fits(byte[] header, int maxWidth, int maxHeight) {
        if (header == null || header.length < LENGTH || maxWidth <= 0 || maxHeight <= 0) return false;
        for (int i = 0; i < SIGNATURE.length; i++) if (header[i] != SIGNATURE[i]) return false;
        if (intAt(header, 8) != 13 || header[12] != 'I' || header[13] != 'H'
                || header[14] != 'D' || header[15] != 'R') return false;
        int width = intAt(header, 16), height = intAt(header, 20);
        // A PNG dimension with the high bit set is invalid, not a small preview.
        return width > 0 && height > 0 && width <= maxWidth && height <= maxHeight;
    }

    private static int intAt(byte[] bytes, int offset) {
        return ((bytes[offset] & 255) << 24) | ((bytes[offset + 1] & 255) << 16)
                | ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
    }

    private PreviewPngHeader() {}
}
