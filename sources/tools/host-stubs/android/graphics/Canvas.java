package android.graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
/** Java2D stand-in; not a rendering equivalence test for Android. */
public final class Canvas {
    private final Bitmap destination;
    public Canvas(Bitmap destination) { this.destination = destination; }
    public void drawBitmap(Bitmap source, Object crop, RectF target, Paint paint) {
        Graphics2D canvas = destination.image.createGraphics();
        canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform transform = new AffineTransform();
        transform.translate(target.left, target.top);
        transform.scale((target.right - target.left) / source.getWidth(),
                (target.bottom - target.top) / source.getHeight());
        canvas.drawImage(source.image, transform, null);
        canvas.dispose();
    }
}
