package app.tvdoctor.clock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.IBinder;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Окно поверх всех приложений с датой и/или временем.
 * Обновляется раз в минуту по системному сигналу TIME_TICK — процессор почти не используется.
 */
public class ClockService extends Service {
    private static final String CHANNEL = "clock";
    private WindowManager wm;
    private LinearLayout box;
    private TextView big, small;
    private boolean attached;
    private Prefs prefs;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            render();
        }
    };

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Часы на экране", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Часы на экране")
                .setOngoing(true)
                .build();
        startForeground(1, n);

        wm = getSystemService(WindowManager.class);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        big = label(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        small = label(Typeface.create("sans-serif", Typeface.NORMAL));
        box.addView(big);
        box.addView(small);

        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        f.addAction(Intent.ACTION_LOCALE_CHANGED);
        registerReceiver(tick, f);
    }

    private TextView label(Typeface tf) {
        TextView t = new TextView(this);
        t.setTypeface(tf);
        t.setTextColor(0xFFFFFFFF);
        t.setIncludeFontPadding(false);
        return t;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        prefs = Prefs.load(this);
        if (!prefs.enabled || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        applyStyle();
        render();
        return START_STICKY;
    }

    private void applyStyle() {
        float s = prefs.size;
        boolean timeFirst = !"date".equals(prefs.mode);
        float bigSp = timeFirst ? s * 1.4f : s;
        float smallSp = s * 0.7f;
        big.setTextSize(TypedValue.COMPLEX_UNIT_SP, bigSp);
        small.setTextSize(TypedValue.COMPLEX_UNIT_SP, smallSp);
        float density = getResources().getDisplayMetrics().density;
        big.setShadowLayer(bigSp * density * 0.18f, 0, bigSp * density * 0.06f, 0xB0000000);
        small.setShadowLayer(smallSp * density * 0.22f, 0, smallSp * density * 0.07f, 0xB0000000);
        small.setVisibility("time".equals(prefs.mode) ? View.GONE : View.VISIBLE);

        boolean right = prefs.corner.endsWith("r");
        boolean bottom = prefs.corner.startsWith("b");
        int h = right ? Gravity.END : Gravity.START;
        box.setGravity(h);
        big.setGravity(h);
        small.setGravity(h);
        box.setAlpha(prefs.alpha / 100f);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = (bottom ? Gravity.BOTTOM : Gravity.TOP) | h;
        // Отступ от краёв с запасом на обрезку картинки телевизором (overscan)
        lp.x = Math.round(Math.max(dm.widthPixels, dm.heightPixels) * 0.035f);
        lp.y = Math.round(Math.min(dm.widthPixels, dm.heightPixels) * 0.05f);
        lp.setTitle("TV Clock");
        try {
            if (attached) wm.updateViewLayout(box, lp);
            else {
                wm.addView(box, lp);
                attached = true;
            }
        } catch (RuntimeException e) {
            stopSelf();
        }
    }

    private void render() {
        if (prefs == null) return;
        Date now = new Date();
        Locale loc = Locale.getDefault();
        String time = DateFormat.is24HourFormat(this)
                ? new SimpleDateFormat("HH:mm", loc).format(now)
                : new SimpleDateFormat("h:mm", loc).format(now);
        String dayMonth = new SimpleDateFormat("d MMMM", loc).format(now);
        String weekday = cap(new SimpleDateFormat("EEEE", loc).format(now));
        switch (prefs.mode) {
            case "time":
                big.setText(time);
                break;
            case "datetime":
                big.setText(time);
                small.setText(cap(new SimpleDateFormat("EE", loc).format(now)) + ", " + dayMonth);
                break;
            default:
                big.setText(dayMonth);
                small.setText(weekday);
        }
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1);
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(tick);
        } catch (RuntimeException ignored) {
        }
        if (attached) {
            try {
                wm.removeView(box);
            } catch (RuntimeException ignored) {
            }
            attached = false;
        }
        super.onDestroy();
    }
}
