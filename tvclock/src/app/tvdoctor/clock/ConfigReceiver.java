package app.tvdoctor.clock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Принимает настройки от TV Doctor:
 *   am broadcast -a app.tvdoctor.clock.CONFIG -n app.tvdoctor.clock/.ConfigReceiver -f 0x20
 *       --es corner tr --ei size 28 --ei alpha 90 --es mode date --ez vpn true
 *   am broadcast -a app.tvdoctor.clock.HIDE -n app.tvdoctor.clock/.ConfigReceiver -f 0x20
 * Защищён разрешением DUMP: отправить может только ADB, не другие приложения.
 */
public class ConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        SharedPreferences.Editor e = c.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit();
        if ("app.tvdoctor.clock.HIDE".equals(i.getAction())) {
            e.putBoolean("enabled", false).apply();
            c.stopService(new Intent(c, ClockService.class));
            setResultData("hidden");
            return;
        }
        if (i.hasExtra("corner")) e.putString("corner", Prefs.oneOf(i.getStringExtra("corner"), Prefs.CORNERS, "tr"));
        if (i.hasExtra("mode")) e.putString("mode", Prefs.oneOf(i.getStringExtra("mode"), Prefs.MODES, "date"));
        if (i.hasExtra("size")) e.putInt("size", Prefs.clamp(i.getIntExtra("size", 28), 14, 72));
        if (i.hasExtra("alpha")) e.putInt("alpha", Prefs.clamp(i.getIntExtra("alpha", 90), 20, 100));
        if (i.hasExtra("vpn")) e.putBoolean("vpn", i.getBooleanExtra("vpn", true));
        e.putBoolean("enabled", true).commit();
        c.startForegroundService(new Intent(c, ClockService.class));
        setResultData("applied");
    }
}
