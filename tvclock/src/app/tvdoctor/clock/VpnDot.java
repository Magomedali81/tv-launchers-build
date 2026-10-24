package app.tvdoctor.clock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Кружок рядом с часами: зелёный — VPN работает, красный — не работает. */
final class VpnDot extends View {
    static final int GREEN = 0xFF34C759, RED = 0xFFFF453A;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    VpnDot(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // тень
        ring.setColor(0x59000000);
        fill.setColor(RED);
    }

    void setOn(boolean on) {
        int c = on ? GREEN : RED;
        if (fill.getColor() != c) {
            fill.setColor(c);
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = d * 0.36f;
        ring.setShadowLayer(d * 0.12f, 0, d * 0.05f, 0x99000000);
        c.drawCircle(cx, cy, r + d * 0.07f, ring);   // тёмная кромка — видно на светлом фоне
        c.drawCircle(cx, cy, r, fill);
    }
}
