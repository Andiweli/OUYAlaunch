
package com.ast.ouyalaunch;

import android.graphics.Rect;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;

public class SpacingDecoration extends RecyclerView.ItemDecoration {
    private final int space;
    public SpacingDecoration(int spacePx) { this.space = spacePx; }
    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
        // Preserve the full gap even if its pixel size is odd: no lost row pixel.
        outRect.left = space/2;
        outRect.right = space - space/2;
        outRect.top = space/2;
        outRect.bottom = space - space/2;
    }
}
