package net.runelite.client.plugins.humanizer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import androidx.core.content.FileProvider;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The permanent session log: a plain-text, always-on record of everything that happens
 * while the client runs, for troubleshooting. One line per event:
 *
 * <pre>HH:mm:ss.SSS  t1234  CAT   message</pre>
 *
 * <p>Categories: TICK (player position / animation / target / HP / run, written when they
 * change), STATUS (each script's status line, on change), INPUT (every gesture the
 * engine sends: taps, presses, long presses, keys, menu picks), MOVE (finger moves, one
 * line per gesture path), LOG (script decisions), MENU (menu options clicked — yours and
 * the scripts'), CHAT, XP, INV (inventory changes), WIDGET (interfaces opened/closed),
 * GAME (login state, deaths, hits on you), CONFIG (setting changes), ERROR.</p>
 *
 * <p>Files live in the app cache under {@code sessionlogs/}, a new file every 8 MB, and
 * the oldest are deleted beyond the configured total. "Share" zips them and opens the
 * Android share sheet.</p>
 */
public final class SessionLog
{
        public static final SessionLog INSTANCE = new SessionLog();
        private static final String TAG = "SessionLog";
        private static final long FILE_BYTES = 8L * 1024 * 1024;

        private volatile boolean enabled = true;
        private volatile boolean moves = true;
        private volatile long maxTotalBytes = 60L * 1024 * 1024;
        private volatile int tick;

        private BufferedWriter out;
        private File file;
        private long written;
        private long lastFlush;
        private String lastError;
        private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
        private final Map<String, Supplier<String>> statusSources = new LinkedHashMap<>();
        private final Map<String, String> lastStatus = new LinkedHashMap<>();
        private final StringBuilder movePath = new StringBuilder();
        private int movePoints;

        private SessionLog()
        {
        }

        // ------------------------------------------------------------------ settings

        public void configure(boolean enabled, boolean moves, int maxTotalMb)
        {
                boolean was = this.enabled;
                this.enabled = enabled;
                this.moves = moves;
                this.maxTotalBytes = Math.max(8, maxTotalMb) * 1024L * 1024L;
                if (was && !enabled)
                {
                        line("GAME", "session log switched off");
                        close();
                }
        }

        public boolean isEnabled()
        {
                return enabled;
        }

        public void setTick(int tick)
        {
                this.tick = tick;
        }

        /** Scripts register their status line; it is written whenever it changes. */
        public synchronized void statusSource(String script, Supplier<String> status)
        {
                if (status == null)
                {
                        statusSources.remove(script);
                        lastStatus.remove(script);
                }
                else
                {
                        statusSources.put(script, status);
                }
        }

        /** Once per game tick (client thread): writes changed script status lines. */
        public void pollStatuses()
        {
                if (!enabled)
                {
                        return;
                }
                Map<String, Supplier<String>> copy;
                synchronized (this)
                {
                        copy = new LinkedHashMap<>(statusSources);
                }
                for (Map.Entry<String, Supplier<String>> e : copy.entrySet())
                {
                        String s;
                        try
                        {
                                s = e.getValue().get();
                        }
                        catch (RuntimeException ex)
                        {
                                s = "status error: " + ex;
                        }
                        String prev;
                        synchronized (this)
                        {
                                prev = lastStatus.put(e.getKey(), s);
                        }
                        if (s != null && !s.equals(prev))
                        {
                                line("STATUS", e.getKey() + ": " + s);
                        }
                }
        }

        // ------------------------------------------------------------------ writing

        /** One line in the given category. Any thread. */
        public void line(String cat, String message)
        {
                if (!enabled)
                {
                        return;
                }
                if (!"MOVE".equals(cat))
                {
                        flushMoves();
                }
                write(cat, message);
        }

        /** A finger move from the input engine: collected into one MOVE line per gesture. */
        public void move(int x, int y)
        {
                if (!enabled || !moves)
                {
                        return;
                }
                synchronized (this)
                {
                        if (movePoints < 400)
                        {
                                movePath.append(movePoints == 0 ? "" : " ").append(x).append(',').append(y);
                        }
                        movePoints++;
                }
        }

        private void flushMoves()
        {
                String path = null;
                int n;
                synchronized (this)
                {
                        n = movePoints;
                        if (n > 0)
                        {
                                path = movePath.toString();
                                movePath.setLength(0);
                                movePoints = 0;
                        }
                }
                if (path != null)
                {
                        write("MOVE", n + " pts: " + path + (n > 400 ? " ..." : ""));
                }
        }

        public void error(String where, Throwable t)
        {
                line("ERROR", where + ": " + t + (t.getStackTrace().length > 0 ? " at " + t.getStackTrace()[0] : ""));
        }

        private synchronized void write(String cat, String message)
        {
                try
                {
                        if (out == null && !open())
                        {
                                return;
                        }
                        String s = clock.format(new Date()) + "  t" + tick + "  " + pad(cat) + message + "\n";
                        out.write(s);
                        written += s.length();
                        long now = System.currentTimeMillis();
                        if (now - lastFlush > 1000)
                        {
                                out.flush();
                                lastFlush = now;
                        }
                        if (written > FILE_BYTES)
                        {
                                close();
                                prune();
                        }
                }
                catch (IOException e)
                {
                        lastError = e.getMessage();
                        close();
                }
        }

        private static String pad(String cat)
        {
                StringBuilder b = new StringBuilder(cat);
                while (b.length() < 7)
                {
                        b.append(' ');
                }
                return b.toString();
        }

        private boolean open()
        {
                File dir = dir();
                if (dir == null)
                {
                        return false;
                }
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
                file = new File(dir, "session-" + stamp + ".log");
                try
                {
                        out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8), 1 << 15);
                        written = 0;
                        out.write("# RuneLite mobile session log, started " + new Date() + "\n");
                        return true;
                }
                catch (IOException e)
                {
                        lastError = e.getMessage();
                        out = null;
                        return false;
                }
        }

        public synchronized void flush()
        {
                try
                {
                        if (out != null)
                        {
                                out.flush();
                        }
                }
                catch (IOException e)
                {
                        lastError = e.getMessage();
                }
        }

        private synchronized void close()
        {
                try
                {
                        if (out != null)
                        {
                                out.flush();
                                out.close();
                        }
                }
                catch (IOException ignored)
                {
                        // nothing to do
                }
                out = null;
        }

        private File[] files()
        {
                File dir = dir();
                File[] all = dir == null ? null : dir.listFiles((d, n) -> n.startsWith("session-") && n.endsWith(".log"));
                if (all == null)
                {
                        return new File[0];
                }
                Arrays.sort(all, (a, b) -> a.getName().compareTo(b.getName()));
                return all;
        }

        private void prune()
        {
                File[] all = files();
                long total = 0;
                for (File f : all)
                {
                        total += f.length();
                }
                for (int i = 0; i < all.length && total > maxTotalBytes; i++)
                {
                        if (all[i].equals(file) && out != null)
                        {
                                continue;
                        }
                        total -= all[i].length();
                        all[i].delete();
                }
        }

        private static Context context()
        {
                try
                {
                        return (Context) Class.forName("android.app.ActivityThread")
                                .getMethod("currentApplication").invoke(null);
                }
                catch (Exception e)
                {
                        return null;
                }
        }

        private static File dir()
        {
                Context ctx = context();
                if (ctx == null)
                {
                        return null;
                }
                File dir = new File(ctx.getCacheDir(), "sessionlogs");
                return dir.isDirectory() || dir.mkdirs() ? dir : null;
        }

        // ------------------------------------------------------------------ panel

        /** "12.3 MB in 3 files" for the Scripts panel. */
        public String summary()
        {
                File[] all = files();
                long total = 0;
                for (File f : all)
                {
                        total += f.length();
                }
                String s = String.format(Locale.ROOT, "%.1f MB in %d file%s", total / 1048576.0d, all.length, all.length == 1 ? "" : "s");
                return lastError == null ? s : s + " (" + lastError + ")";
        }

        /** Zips every session log and opens the Android share sheet. Any thread. */
        public void share()
        {
                line("GAME", "sharing the session log");
                flush();
                new Thread(() ->
                {
                        Context ctx = context();
                        File dir = dir();
                        if (ctx == null || dir == null)
                        {
                                lastError = "no app context";
                                return;
                        }
                        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
                        File zip = buildZip(dir, stamp);
                        if (zip == null)
                        {
                                return;
                        }
                        try
                        {
                                Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".updates", zip);
                                Intent send = new Intent(Intent.ACTION_SEND);
                                send.setType("application/zip");
                                send.putExtra(Intent.EXTRA_STREAM, uri);
                                send.putExtra(Intent.EXTRA_SUBJECT, "RuneLite session log " + stamp);
                                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                Intent chooser = Intent.createChooser(send, "Share session log");
                                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                ctx.startActivity(chooser);
                        }
                        catch (RuntimeException e)
                        {
                                lastError = "share failed: " + e.getMessage();
                                Log.e(TAG, "share failed", e);
                        }
                }, "session-log-share").start();
        }

        private File buildZip(File dir, String stamp)
        {
                File zip = new File(dir, "sessionlog-" + stamp + ".zip");
                File[] old = dir.listFiles((d, n) -> n.startsWith("sessionlog-") && n.endsWith(".zip"));
                if (old != null)
                {
                        for (File f : old)
                        {
                                f.delete();
                        }
                }
                try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip)))
                {
                        byte[] buf = new byte[1 << 16];
                        for (File f : files())
                        {
                                z.putNextEntry(new ZipEntry(f.getName()));
                                try (FileInputStream in = new FileInputStream(f))
                                {
                                        int r;
                                        while ((r = in.read(buf)) > 0)
                                        {
                                                z.write(buf, 0, r);
                                        }
                                }
                                z.closeEntry();
                        }
                        return zip;
                }
                catch (IOException e)
                {
                        lastError = "zip failed: " + e.getMessage();
                        return null;
                }
        }

        private volatile String savedTo;

        /** Where the last "Save" put the zip, for the panel ("Download/RuneLite/..."). */
        public String savedTo()
        {
                return savedTo;
        }

        /**
         * Zips every session log into the phone's public Downloads folder, under
         * Download/RuneLite/, where any file manager can open it. Any thread.
         */
        public void saveToDownloads()
        {
                line("GAME", "saving the session log to Downloads");
                flush();
                new Thread(() ->
                {
                        Context ctx = context();
                        File dir = dir();
                        if (ctx == null || dir == null)
                        {
                                lastError = "no app context";
                                return;
                        }
                        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
                        File zip = buildZip(dir, stamp);
                        if (zip == null)
                        {
                                return;
                        }
                        String name = zip.getName();
                        try
                        {
                                if (android.os.Build.VERSION.SDK_INT >= 29)
                                {
                                        android.content.ContentValues v = new android.content.ContentValues();
                                        v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                                        v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                                        v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/RuneLite");
                                        Uri uri = ctx.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                                        if (uri == null)
                                        {
                                                lastError = "could not create the file in Downloads";
                                                return;
                                        }
                                        try (java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                                             FileInputStream in = new FileInputStream(zip))
                                        {
                                                byte[] buf = new byte[1 << 16];
                                                int r;
                                                while ((r = in.read(buf)) > 0)
                                                {
                                                        os.write(buf, 0, r);
                                                }
                                        }
                                        savedTo = "Download/RuneLite/" + name;
                                }
                                else
                                {
                                        File ext = ctx.getExternalFilesDir(null);
                                        File out = new File(ext, name);
                                        try (FileInputStream in = new FileInputStream(zip); FileOutputStream os = new FileOutputStream(out))
                                        {
                                                byte[] buf = new byte[1 << 16];
                                                int r;
                                                while ((r = in.read(buf)) > 0)
                                                {
                                                        os.write(buf, 0, r);
                                                }
                                        }
                                        savedTo = out.getAbsolutePath();
                                }
                                lastError = null;
                                line("GAME", "session log saved to " + savedTo);
                        }
                        catch (IOException | RuntimeException e)
                        {
                                lastError = "save failed: " + e.getMessage();
                        }
                }, "session-log-save").start();
        }

        /** Deletes every session log file (and starts a fresh one on the next line). */
        public synchronized void clear()
        {
                close();
                for (File f : files())
                {
                        f.delete();
                }
                lastError = null;
        }

        /** Unused-import guard for builds that strip ArrayList. */
        @SuppressWarnings("unused")
        private static final Class<?> KEEP = ArrayList.class;
}
