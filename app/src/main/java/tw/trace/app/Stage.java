package tw.trace.app;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/** The whole drawn surface: passes touches to the board and runs the animation loop. */
final class Stage extends View {
    private final Board b;
    private final float dens;
    private Painter painter;
    private boolean uiGesture = false, running = false;
    private long lastDraw = 0;

    Stage(Context c, Board b) {
        super(c);
        this.b = b;
        dens = c.getResources().getDisplayMetrics().density;
        setFocusable(true);
    }

    void setPainter(Painter p) { painter = p; }

    void setRunning(boolean on) {
        running = on;
        if (on) invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        b.resized(w / dens, h / dens);
    }

    @Override protected void onDraw(Canvas c) {
        if (painter == null) return;
        long now = SystemClock.uptimeMillis();
        boolean settling = b.step(now);
        int save = c.save();
        c.scale(dens, dens);
        painter.draw(c, now);
        c.restoreToCount(save);
        b.need = false;
        lastDraw = now;
        if (!running) return;
        boolean moving = settling || b.moving();
        if (moving) postInvalidateOnAnimation();
        else if (!b.reduce) postInvalidateDelayed(33);   // the idle animations don't need the full rate
        else if (b.toastMsg != null) postInvalidateDelayed(250);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked(), ix = e.getActionIndex(), pid = e.getPointerId(ix);
        double x = e.getX(ix) / dens, y = e.getY(ix) / dens, now = SystemClock.uptimeMillis();
        switch (a) {
            case MotionEvent.ACTION_DOWN:
                uiGesture = b.touchDown(pid, x, y, true);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (!uiGesture) b.touchDown(pid, x, y, false);
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    double px = e.getX(i) / dens, py = e.getY(i) / dens;
                    if (uiGesture) { if (i == 0) b.touchMove(id, px, py); }
                    else b.pointerMove(id, px, py);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (!uiGesture) b.pointerUp(pid, x, y, false, now);
                break;
            case MotionEvent.ACTION_UP:
                if (!uiGesture) { b.pointerUp(pid, x, y, false, now); b.touchUp(x, y, true); }
                else b.touchUp(x, y, false);
                uiGesture = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < e.getPointerCount(); i++) if (!uiGesture) b.pointerUp(e.getPointerId(i), x, y, true, now);
                b.touchUp(x, y, true);
                uiGesture = false;
                break;
            default:
                break;
        }
        postInvalidateOnAnimation();
        return true;
    }
}
