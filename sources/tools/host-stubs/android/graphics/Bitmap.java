package android.graphics;
import java.awt.image.BufferedImage;
/** Host test double backed by Java2D. Does not model Android/Skia decoding. */
public final class Bitmap {
    public enum Config { ARGB_8888 }
    final BufferedImage image;
    private boolean recycled;
    Bitmap(BufferedImage image) { this.image = image; }
    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB));
    }
    public int getWidth() { return image.getWidth(); }
    public int getHeight() { return image.getHeight(); }
    public int getRowBytes() { return getWidth() * 4; }
    public int getPixel(int x, int y) { return image.getRGB(x, y); }
    public boolean isRecycled() { return recycled; }
    public void recycle() { recycled = true; }
}
