package com.ast.ouyalaunch;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import java.io.IOException;
import java.io.InputStream;

/** Software ARGB previews retain transparency and enough detail for the focus zoom. */
final class BitmapTools {
    static final int WIDTH = 484;
    static final int HEIGHT = 273;
    static final int CACHE_VERSION = 1;

    interface Source { InputStream open() throws IOException; }

    static Bitmap decode(Source source) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream stream = source.open();
        try { BitmapFactory.decodeStream(stream, null, bounds); }
        finally { stream.close(); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= WIDTH
                && bounds.outHeight / (options.inSampleSize * 2) >= HEIGHT) {
            options.inSampleSize *= 2;
        }
        stream = source.open();
        Bitmap decoded;
        try { decoded = BitmapFactory.decodeStream(stream, null, options); }
        finally { stream.close(); }
        if (decoded == null) return null;
        // Match ImageView centerCrop, but do not enlarge small source images on disk.
        float scale = Math.min(1f, Math.max((float) WIDTH / decoded.getWidth(),
                (float) HEIGHT / decoded.getHeight()));
        int width = Math.min(WIDTH, Math.max(1, Math.round(decoded.getWidth() * scale)));
        int height = Math.min(HEIGHT, Math.max(1, Math.round(decoded.getHeight() * scale)));
        if (width == decoded.getWidth() && height == decoded.getHeight()) return decoded;
        Bitmap result = null;
        try {
            result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            float left = (width - decoded.getWidth() * scale) / 2f;
            float top = (height - decoded.getHeight() * scale) / 2f;
            new Canvas(result).drawBitmap(decoded, null,
                    new RectF(left, top, left + decoded.getWidth() * scale,
                            top + decoded.getHeight() * scale),
                    new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
            return result;
        } catch (RuntimeException | OutOfMemoryError error) {
            if (result != null) result.recycle();
            throw error;
        } finally {
            decoded.recycle();
        }
    }

    private BitmapTools() { }
}
