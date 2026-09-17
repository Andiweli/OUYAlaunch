package com.ast.ouyalaunch;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** Keep controller focus and the viewport in sync, including repeated DPAD events. */
final class ControllerGridLayoutManager extends GridLayoutManager {
    ControllerGridLayoutManager(Context context, int spans) { super(context, spans); }

    @Override public View onInterceptFocusSearch(View focused, int direction) {
        if (direction == View.FOCUS_UP) {
            int position = getPosition(focused);
            if (position >= 0 && position < getSpanCount()) return focused;
        }
        return super.onInterceptFocusSearch(focused, direction);
    }

    @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child,
            Rect rect, boolean immediate, boolean focusedChildVisible) {
        // RecyclerView otherwise starts another smooth scroll for each focus move. A held
        // direction can reverse the still-running correction from the preceding row.
        parent.stopScroll();
        return super.requestChildRectangleOnScreen(parent, child, rect, true, focusedChildVisible);
    }

    @Override public boolean supportsPredictiveItemAnimations() { return false; }
}
