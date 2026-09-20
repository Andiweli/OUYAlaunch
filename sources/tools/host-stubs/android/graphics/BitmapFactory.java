package android.graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
/** Java2D stand-in. Tests production stream handling/branching, not Skia performance. */
public final class BitmapFactory {
    public static final class Options {
        public boolean inJustDecodeBounds;
        public int outWidth = -1, outHeight = -1;
        public int inSampleSize = 1;
        public Bitmap.Config inPreferredConfig;
    }
    public static Bitmap decodeStream(InputStream stream, Object padding, Options options) {
        try {
            BufferedImage original = ImageIO.read(stream);
            if (original == null) return null;
            options.outWidth = original.getWidth(); options.outHeight = original.getHeight();
            if (options.inJustDecodeBounds) return null;
            int sample = Math.max(1, options.inSampleSize);
            BufferedImage decoded = new BufferedImage(Math.max(1, original.getWidth() / sample),
                    Math.max(1, original.getHeight() / sample), BufferedImage.TYPE_INT_ARGB);
            Graphics2D canvas = decoded.createGraphics();
            canvas.drawImage(original, 0, 0, decoded.getWidth(), decoded.getHeight(), null);
            canvas.dispose();
            return new Bitmap(decoded);
        } catch (IOException error) { return null; }
    }
}
