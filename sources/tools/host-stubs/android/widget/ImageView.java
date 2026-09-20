package android.widget;
import android.graphics.Bitmap;
/** Host test double: checks request tagging/binding, not Android layout or drawing. */
public final class ImageView {
    private Object tag;
    private Bitmap bitmap;
    public ImageView(Object context) {}
    public Object getTag() { return tag; }
    public void setTag(Object tag) { this.tag = tag; }
    public void setImageBitmap(Bitmap bitmap) { this.bitmap = bitmap; }
    public void setImageDrawable(Object drawable) {
        if (drawable != null) throw new UnsupportedOperationException("Only drawable clearing is modelled");
        bitmap = null;
    }
    public Object getDrawable() { return bitmap; }
}
