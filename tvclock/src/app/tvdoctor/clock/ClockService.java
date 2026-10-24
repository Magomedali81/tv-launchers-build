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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
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

import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
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
    private LinearLayout row, row2;
    private VpnDot vpn;
    private ConnectivityManager cm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.Set<Network> vpnNets = new java.util.HashSet<>();

    /** Сеть VPN появилась или пропала — проверяем сразу, не дожидаясь следующей минуты. */
    private final ConnectivityManager.NetworkCallback netCb = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network n) {
            synchronized (vpnNets) { vpnNets.add(n); }
            main.post(ClockService.this::updateVpn);
        }

        @Override
        public void onLost(Network n) {
            synchronized (vpnNets) { vpnNets.remove(n); }
            main.post(ClockService.this::updateVpn);
        }
    };
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
        vpn = new VpnDot(this);
        vpn.setVisibility(View.GONE);
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(big);
        row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.addView(small);
        box.addView(row);
        box.addView(row2);
        cm = getSystemService(ConnectivityManager.class);
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), netCb);
        } catch (RuntimeException ignored) {
            // без обратного вызова — проверка раз в минуту
        }

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
        row2.setVisibility("time".equals(prefs.mode) ? View.GONE : View.VISIBLE);

        boolean right = prefs.corner.endsWith("r");
        boolean bottom = prefs.corner.startsWith("b");
        int h = right ? Gravity.END : Gravity.START;
        box.setGravity(h);
        big.setGravity(h);
        small.setGravity(h);
        box.setAlpha(prefs.alpha / 100f);
        // Кружок VPN: строка (верхняя/нижняя), сторона (слева/справа от текста), размер в % высоты строки
        ((LinearLayout) row).removeView(vpn);
        row2.removeView(vpn);
        boolean lower = "small".equals(prefs.dotRow) && !"time".equals(prefs.mode);
        float lineSp = lower ? smallSp : bigSp;
        int dotPx = Math.max(4, Math.round(lineSp * getResources().getDisplayMetrics().scaledDensity * prefs.dotSize / 100f));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dotPx, dotPx);
        int gap = Math.round(Math.max(dotPx * 0.45f, lineSp * 0.15f * density));
        boolean dotLeft = "l".equals(prefs.dotSide);
        if (dotLeft) ip.rightMargin = gap; else ip.leftMargin = gap;
        LinearLayout target = lower ? row2 : row;
        target.addView(vpn, dotLeft ? 0 : target.getChildCount(), ip);

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

    /** VPN работает? Три способа: сети VPN системы, их обратный вызов и интерфейсы tun/wg (видны всегда,
     *  даже если часы не входят в раздельное туннелирование). */
    private boolean vpnOn() {
        synchronized (vpnNets) {
            if (!vpnNets.isEmpty()) return true;
        }
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (RuntimeException ignored) {
        }
        try {
            for (NetworkInterface i : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String n = i.getName();
                if (i.isUp() && n != null && n.matches("(tun|tap|ppp|wg|awg|utun|ipsec)\\d+")) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void updateVpn() {
        if (prefs == null || vpn == null) return;
        vpn.setVisibility(prefs.vpn ? View.VISIBLE : View.GONE);
        if (prefs.vpn) vpn.setOn(vpnOn());
    }

    private void render() {
        if (prefs == null) return;
        updateVpn();
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
        try {
            cm.unregisterNetworkCallback(netCb);
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
