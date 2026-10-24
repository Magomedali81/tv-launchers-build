package com.maydings.vibelauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wallpapers behind Home when the focused app has no still of its own: Wikimedia Commons' "Picture of the
 * day" for the last month, changing every 10 minutes.
 *
 * Only landscape pictures are kept. Each is downloaded once at the screen's size from upload.wikimedia.org,
 * cut to the screen and saved as JPEG; the darkening gradient (the same one as the app stills) is applied
 * after decoding, so the saved file is never re-compressed dark (that banded). One decoded picture is held
 * at a time (8 MB at 1080p) and let go on onTrimMemory.
 *
 * Added by TV Doctor (github.com/Magomedali81/tv-launchers-build) on top of maydings/vibe-launcher.
 */
final class Wallpapers {

    interface Listener {
        /** A new picture is ready (first download, or the 10-minute change). Main thread. */
        void onWallpaper();
    }

    static final long SLOT_MS = 10L * 60 * 1000;
    private static final long REFRESH_MS = 6L * 60 * 60 * 1000;
    private static final long RETRY_MS = 2L * 60 * 1000;   // nothing downloaded yet: try again soon
    private static final String TAG = "VibeWallpapers";
    private static final int DAYS = 30;
    private static final int MAX_JSON = 2 * 1024 * 1024;
    private static final int MAX_IMAGE = 12 * 1024 * 1024;
    private static final String[] FEEDS = {
            "https://ru.wikipedia.org/api/rest_v1/feed/featured/",
            "https://en.wikipedia.org/api/rest_v1/feed/featured/",
            "https://api.wikimedia.org/feed/v1/wikipedia/en/featured/",
    };
    private static final String[] IMAGE_HOSTS = {"https://upload.wikimedia.org/", "https://thumb.wikimedia.org/"};
    private static final Map<String, String> HEADERS = new HashMap<>();

    static {
        HEADERS.put("User-Agent", "VibeLauncher-TVDoctor/1.0 (https://github.com/Magomedali81/tv-launchers-build)");
        HEADERS.put("Accept", "application/json");
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wallpapers");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final File dir;
    private final File stamp;
    private final File status;   // a short log TV Doctor reads over ADB (Android/data/<pkg>/files)
    private final int width, height;
    private final Listener listener;

    // Main thread only.
    private Bitmap bitmap;
    private String credit;
    private String shownName;
    private boolean decoding;
    private boolean started;
    private boolean closed;
    private boolean loggedStart;
    private volatile boolean refreshing;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!started || closed) return;
            prepare(true);
            scheduleTick();
        }
    };

    Wallpapers(Context context, Listener listener) {
        this.listener = listener;
        dir = new File(context.getFilesDir(), "wallpapers");
        stamp = new File(dir, ".refreshed");
        File ext = null;
        try {
            ext = context.getExternalFilesDir(null);
        } catch (RuntimeException ignored) {
            // no shared storage
        }
        status = ext != null ? new File(ext, "wallpapers-status.txt") : null;
        int w = context.getResources().getDisplayMetrics().widthPixels;
        int h = context.getResources().getDisplayMetrics().heightPixels;
        width = Math.max(w, h);
        height = Math.min(w, h);
    }

    /** Home is on screen: refresh if due, get the current picture ready and follow the 10-minute changes. */
    void start() {
        if (closed) return;
        started = true;
        if (!loggedStart) {
            loggedStart = true;
            log("start: " + list().length + " wallpapers on disk");
        }
        refreshIfDue();
        prepare(true);
        scheduleTick();
    }

    void stop() {
        started = false;
        main.removeCallbacks(tick);
    }

    /** Let the decoded picture go (Home is in the background and memory is low). */
    void trim() {
        bitmap = null;
        credit = null;
        shownName = null;
    }

    void close() {
        closed = true;
        stop();
        trim();
        worker.shutdownNow();
    }

    /** The picture for now, or null (none downloaded yet, or still decoding: the listener follows). */
    Bitmap current() {
        prepare(false);
        return bitmap;
    }

    /** A short caption for the current picture (its description or author), or null. */
    String credit() {
        return bitmap != null ? credit : null;
    }

    private void scheduleTick() {
        main.removeCallbacks(tick);
        long now = System.currentTimeMillis();
        long next = (now / SLOT_MS + 1) * SLOT_MS + 1000;
        main.postDelayed(tick, next - now);
    }

    /** Decodes the picture for the current slot on the worker, if it isn't the one held. */
    private void prepare(boolean notify) {
        if (closed || decoding) return;
        final File[] files = list();
        if (files.length == 0) return;
        final File pick = files[(int) ((System.currentTimeMillis() / SLOT_MS) % files.length)];
        if (pick.getName().equals(shownName) && bitmap != null) return;
        decoding = true;
        worker.execute(() -> {
            Bitmap b = decodeDarkened(pick);
            if (b == null) log("decode failed " + pick.getName());
            String c = readCredit(pick);
            main.post(() -> {
                decoding = false;
                if (closed || b == null) return;
                bitmap = b;
                credit = c;
                shownName = pick.getName();
                if (started || notify) listener.onWallpaper();
            });
        });
    }

    private File[] list() {
        File[] files = dir.listFiles((d, name) -> name.startsWith("potd-") && name.endsWith(".jpg"));
        if (files == null) return new File[0];
        Arrays.sort(files);
        return files;
    }

    private void refreshIfDue() {
        if (refreshing) return;
        long age = System.currentTimeMillis() - stamp.lastModified();
        boolean empty = list().length == 0;
        if (stamp.exists() && age >= 0 && age < (empty ? RETRY_MS : REFRESH_MS)) return;
        refreshing = true;
        worker.execute(() -> {
            int added = 0;
            try {
                added = refresh();
            } finally {
                refreshing = false;
            }
            if (added > 0) main.post(() -> {
                if (!closed && bitmap == null) prepare(true);
            });
        });
    }

    /** Fetches the missing days. Worker thread. Returns how many new pictures were saved. */
    private int refresh() {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        SimpleDateFormat path = new SimpleDateFormat("yyyy/MM/dd", Locale.US);
        SimpleDateFormat name = new SimpleDateFormat("yyyyMMdd", Locale.US);
        TimeZone utc = TimeZone.getTimeZone("UTC");
        path.setTimeZone(utc);
        name.setTimeZone(utc);
        Calendar day = Calendar.getInstance(utc);
        List<String> keep = new ArrayList<>();
        int added = 0, failures = 0;
        log("refresh start, have " + list().length + ", screen " + width + "x" + height);
        for (int i = 0; i < DAYS && failures < 3; i++) {
            String stem = "potd-" + name.format(day.getTime());
            keep.add(stem);
            File jpg = new File(dir, stem + ".jpg");
            File skip = new File(dir, stem + ".skip");
            if (!jpg.exists() && !skip.exists()) {
                try {
                    if (fetchDay(path.format(day.getTime()), jpg)) {
                        added++;
                        log("saved " + jpg.getName());
                        // The first picture shows at once, not after the whole month is in.
                        main.post(() -> {
                            if (!closed && bitmap == null) prepare(true);
                        });
                    } else {
                        touch(skip);
                        log("skipped " + stem + " (portrait or small)");
                    }
                } catch (IOException | RuntimeException e) {
                    failures++;   // offline, or the feed changed: try again on the next refresh
                    log("failed " + stem + ": " + e);
                }
            }
            day.add(Calendar.DAY_OF_MONTH, -1);
        }
        // Older days go.
        File[] all = dir.listFiles();
        if (all != null) for (File f : all) {
            String n = f.getName();
            if (!n.startsWith("potd-")) continue;
            int dot = n.indexOf('.');
            if (dot > 0 && !keep.contains(n.substring(0, dot))) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
        if (failures == 0 || added > 0) touch(stamp);
        else if (list().length > 0) touch(stamp);   // offline but have pictures: no hurry
        log("refresh done: +" + added + ", failures " + failures + ", have " + list().length);
        return added;
    }

    /** Saves one day's picture. False when that day's picture doesn't suit a TV (portrait, too small). */
    private boolean fetchDay(String ymd, File out) throws IOException {
        JSONObject image = null;
        IOException last = null;
        for (String feed : FEEDS) {
            try {
                JSONObject o = new JSONObject(Http.getText(feed + ymd, HEADERS));
                image = o.optJSONObject("image");
                if (image != null) break;
            } catch (IOException e) {
                last = e;
            } catch (org.json.JSONException e) {
                last = new IOException(e);
            }
        }
        if (image == null) {
            if (last != null) throw last;
            return false;   // no picture that day
        }
        JSONObject original = image.optJSONObject("image");
        JSONObject thumb = image.optJSONObject("thumbnail");
        if (original == null || thumb == null) return false;
        int ow = original.optInt("width"), oh = original.optInt("height");
        if (ow < 1280 || oh <= 0 || ow < oh * 5 / 4) return false;   // a portrait or square picture: skipped
        int want = width > 1280 ? 1920 : 1280;
        // Thumbnails come from thumb.wikimedia.org (formerly upload.wikimedia.org), at a standard width
        // (960, 1280, 1920...), with tracking parameters on the end that we drop.
        String thumbUrl = thumb.optString("source").replaceFirst("[?#].*$", "");
        List<String> urls = new ArrayList<>();
        for (int w : new int[]{want, 1280, 960}) {
            if (w > ow) continue;
            String u = thumbUrl.replaceFirst("/(lossy-page1-|page1-)?\\d+px-([^/]+)$", "/$1" + w + "px-$2");
            if (!urls.contains(u)) urls.add(u);
        }
        if (ow <= want) urls.add(0, original.optString("source").replaceFirst("[?#].*$", ""));
        byte[] data = null;
        IOException failed = null;
        for (String url : urls) {
            if (!allowedImage(url)) continue;
            try {
                data = Http.get(url, Collections.singletonMap("User-Agent", HEADERS.get("User-Agent")), MAX_IMAGE);
                break;
            } catch (IOException e) {
                failed = e;
            }
        }
        if (data == null) {
            if (failed != null) throw failed;
            return false;
        }
        Bitmap cut = cropToScreen(data);
        if (cut == null) return false;
        File tmp = new File(dir, out.getName() + ".tmp");
        try (FileOutputStream f = new FileOutputStream(tmp)) {
            cut.compress(Bitmap.CompressFormat.JPEG, 92, f);
        } finally {
            cut.recycle();
        }
        if (!tmp.renameTo(out)) throw new IOException("rename");
        writeCredit(out, caption(image));
        return true;
    }

    private static boolean allowedImage(String url) {
        for (String h : IMAGE_HOSTS) if (url.startsWith(h)) return true;
        return false;
    }

    /** The description's first sentence, short enough for the corner, else the author. */
    private static String caption(JSONObject image) {
        JSONObject d = image.optJSONObject("description");
        String text = d != null ? d.optString("text", "").trim() : "";
        text = text.replaceAll("\\s+", " ");
        int stop = text.indexOf(". ");
        if (stop > 0) text = text.substring(0, stop);
        if (text.length() > 90) text = text.substring(0, 88).trim() + "…";
        if (text.isEmpty()) {
            JSONObject a = image.optJSONObject("artist");
            text = a != null ? a.optString("text", "").trim().replaceAll("\\s+", " ") : "";
            if (text.length() > 60) text = text.substring(0, 58).trim() + "…";
        }
        return text.isEmpty() ? null : text;
    }

    private Bitmap cropToScreen(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= width && o.outHeight / (o.inSampleSize * 2) >= height) {
            o.inSampleSize *= 2;
        }
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, centreCrop(src.getWidth(), src.getHeight(), width, height),
                new Rect(0, 0, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        return out;
    }

    private static Bitmap decodeDarkened(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inMutable = true;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;   // 16-bit colour bands in the dark gradient
        Bitmap b;
        try {
            b = BitmapFactory.decodeFile(file.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
        if (b == null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();   // damaged: fetched again on the next refresh
            return null;
        }
        Canvas canvas = new Canvas(b);
        Paint scrim = new Paint();
        scrim.setShader(new LinearGradient(0, 0, 0, b.getHeight(),
                new int[]{0xB3000000, 0x40000000, 0xF2000000}, new float[]{0f, 0.38f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, b.getWidth(), b.getHeight(), scrim);
        return b;
    }

    private static Rect centreCrop(int w, int h, int cellW, int cellH) {
        if (w * cellH > h * cellW) {
            int cw = h * cellW / cellH;
            return new Rect((w - cw) / 2, 0, (w + cw) / 2, h);
        }
        int ch = w * cellH / cellW;
        return new Rect(0, (h - ch) / 2, w, (h + ch) / 2);
    }

    private static File creditFile(File jpg) {
        return new File(jpg.getParentFile(), jpg.getName().replace(".jpg", ".txt"));
    }

    private static void writeCredit(File jpg, String text) {
        if (text == null) return;
        try (FileOutputStream f = new FileOutputStream(creditFile(jpg))) {
            f.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // no caption then
        }
    }

    private static String readCredit(File jpg) {
        File f = creditFile(jpg);
        if (!f.exists() || f.length() > 4096) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            String s = new String(Http.readAll(in, 4096), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            return null;
        }
    }

    private synchronized void log(String line) {
        Log.i(TAG, line);
        if (status == null) return;
        try {
            String old = "";
            if (status.exists() && status.length() < 16384) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(status)) {
                    old = new String(Http.readAll(in, 16384), StandardCharsets.UTF_8);
                }
            }
            String[] lines = old.split("\n");
            StringBuilder b = new StringBuilder();
            for (int i = Math.max(0, lines.length - 80); i < lines.length; i++) {
                if (!lines[i].isEmpty()) b.append(lines[i]).append('\n');
            }
            b.append(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new java.util.Date()))
                    .append(' ').append(line).append('\n');
            //noinspection ResultOfMethodCallIgnored
            status.getParentFile().mkdirs();
            try (FileOutputStream f = new FileOutputStream(status)) {
                f.write(b.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ignored) {
            // the log is only for diagnosis
        }
    }

    private static void touch(File f) {
        try {
            if (!f.exists()) new FileOutputStream(f).close();
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
        } catch (IOException ignored) {
            // retried sooner then
        }
    }
}
