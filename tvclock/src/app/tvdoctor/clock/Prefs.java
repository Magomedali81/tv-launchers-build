package app.tvdoctor.clock;

import android.content.Context;
import android.content.SharedPreferences;

/** Настройки часов с проверкой значений. */
final class Prefs {
    static final String FILE = "clock";
    static final String[] CORNERS = {"tl", "tr", "bl", "br"};
    static final String[] MODES = {"date", "time", "datetime"};

    final String corner;
    final int size;      // кегль основной строки, sp: 14..72
    final int alpha;     // непрозрачность, %: 20..100
    final String mode;   // date | time | datetime
    final boolean enabled;
    final boolean vpn;   // кружок состояния VPN: зелёный/красный

    private Prefs(SharedPreferences p) {
        corner = oneOf(p.getString("corner", "tr"), CORNERS, "tr");
        size = clamp(p.getInt("size", 28), 14, 72);
        alpha = clamp(p.getInt("alpha", 90), 20, 100);
        mode = oneOf(p.getString("mode", "date"), MODES, "date");
        enabled = p.getBoolean("enabled", false);
        vpn = p.getBoolean("vpn", true);
    }

    static Prefs load(Context c) {
        return new Prefs(c.getSharedPreferences(FILE, Context.MODE_PRIVATE));
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static String oneOf(String v, String[] allowed, String def) {
        if (v != null) for (String a : allowed) if (a.equals(v)) return v;
        return def;
    }
}
