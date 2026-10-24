package app.tvdoctor.clock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Запускает часы после включения ТВ и после обновления приложения, если они были включены. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (Prefs.load(c).enabled) {
            c.startForegroundService(new Intent(c, ClockService.class));
        }
    }
}
