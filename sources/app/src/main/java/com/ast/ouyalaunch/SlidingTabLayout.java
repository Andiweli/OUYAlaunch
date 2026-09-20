package com.ast.ouyalaunch;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;

/** One painted selection behind the labels; animation never requests a layout. */
public final class SlidingTabLayout extends LinearLayout {
    private final Paint paint = new Paint();
    private ValueAnimator motion;
    private int selected = -1;
    private float left, right, targetLeft, targetRight;
    private boolean positioned;

    public SlidingTabLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(context.getResources().getColor(R.color.gray_selected));
    }

    public void select(int index) {
        if (selected == index && positioned) return;
        selected = index;
        move(true);
    }

    /** Stop hidden-frame work and leave the highlight at its selected destination. */
    void finishAnimation() { move(false); }

    /** Hide the tab marker while the controller is navigating the upper action bar. */
    void clearSelection() {
        if (motion != null) { motion.cancel(); motion = null; }
        selected = -1;
        positioned = false;
        invalidate();
    }

    private void move(boolean animate) {
        if (motion != null) { motion.cancel(); motion = null; }
        View target = selected >= 0 && selected < getChildCount() ? getChildAt(selected) : null;
        if (target == null || target.getVisibility() != VISIBLE || target.getWidth() == 0) {
            positioned = false;
            invalidate();
            return;
        }
        final float endLeft = target.getLeft(), endRight = target.getRight();
        targetLeft = endLeft; targetRight = endRight;
        if (!animate || !positioned) {
            left = endLeft; right = endRight; positioned = true; invalidate(); return;
        }
        final float startLeft = left, startRight = right;
        motion = ValueAnimator.ofFloat(0f, 1f);
        motion.setDuration(140);
        motion.setInterpolator(new DecelerateInterpolator());
        motion.addUpdateListener(value -> {
            float fraction = (Float) value.getAnimatedValue();
            left = startLeft + (endLeft - startLeft) * fraction;
            right = startRight + (endRight - startRight) * fraction;
            invalidate();
        });
        motion.start();
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // Renaming or hiding tabs changes their geometry, even if our own size is unchanged.
        View target = selected >= 0 && selected < getChildCount() ? getChildAt(selected) : null;
        if (!positioned || target == null || target.getVisibility() != VISIBLE
                || target.getLeft() != targetLeft || target.getRight() != targetRight) move(false);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (positioned) canvas.drawRect(left, 0, right, getHeight(), paint);
        super.dispatchDraw(canvas);
    }

    @Override protected void onDetachedFromWindow() {
        if (motion != null) { motion.cancel(); motion = null; }
        super.onDetachedFromWindow();
    }
}
