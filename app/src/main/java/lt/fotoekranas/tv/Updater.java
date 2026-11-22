package lt.fotoekranas.tv;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Programėlės atnaujinimas iš GitHub (naujausia versija). */
final class Updater {

    static final String BASE = "https://github.com/promastatv-sketch/tv-ekranas/releases/download/latest/";

    interface Callback {
        void status(String text);
    }

    static volatile int remoteVersion = 0;
    private static volatile boolean busy = false;

    private Updater() {
    }

    @SuppressWarnings("deprecation")
    static int localVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static boolean updateAvailable(Context c) {
        return remoteVersion > localVersion(c);
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setInstanceFollowRedirects(true);
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("Cache-Control", "no-cache");
        return con;
    }

    /** Patikrina, ar yra naujesnė versija. Kviesti ne pagrindinėje gijoje. */
    static int fetchRemoteVersion() {
        try {
            HttpURLConnection con = open(BASE + "version.txt?t=" + System.currentTimeMillis());
            try {
                if (con.getResponseCode() != 200) return remoteVersion;
                BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()));
                String line = r.readLine();
                r.close();
                if (line != null) remoteVersion = Integer.parseInt(line.trim());
            } finally {
                con.disconnect();
            }
        } catch (Exception ignored) {
        }
        return remoteVersion;
    }

    static void run(final Activity a, final Callback cb) {
        if (busy) {
            cb.status("Atnaujinimas jau vyksta…");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            cb.status("Leiskite programėlei diegti atnaujinimus ir spauskite „Atnaujinti“ dar kartą.");
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()));
                a.startActivity(i);
            } catch (Exception e) {
                cb.status("Nustatymuose leiskite „Foto ekranas“ diegti nežinomas programas.");
            }
            return;
        }
        busy = true;
        cb.status("Tikrinama, ar yra nauja versija…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int remote = fetchRemoteVersion();
                    if (remote <= localVersion(a)) {
                        cb.status("Įdiegta naujausia versija.");
                        return;
                    }
                    cb.status("Atsisiunčiama nauja versija…");
                    File apk = new File(a.getCacheDir(), "update.apk");
                    HttpURLConnection con = open(BASE + "FotoEkranas.apk?t=" + System.currentTimeMillis());
                    try {
                        if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
                        InputStream in = con.getInputStream();
                        OutputStream out = new FileOutputStream(apk);
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        out.close();
                        in.close();
                    } finally {
                        con.disconnect();
                    }

                    PackageInstaller pi = a.getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams params =
                            new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    int id = pi.createSession(params);
                    PackageInstaller.Session s = pi.openSession(id);
                    OutputStream out = s.openWrite("app", 0, apk.length());
                    FileInputStream fin = new FileInputStream(apk);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = fin.read(buf)) > 0) out.write(buf, 0, n);
                    fin.close();
                    s.fsync(out);
                    out.close();

                    Intent i = new Intent(a, InstallReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent p = PendingIntent.getBroadcast(a, id, i, flags);
                    s.commit(p.getIntentSender());
                    s.close();
                    cb.status("Paspauskite „Įdiegti“ (Install).");
                } catch (Exception e) {
                    cb.status("Nepavyko atnaujinti. Patikrinkite interneto ryšį.");
                } finally {
                    busy = false;
                }
            }
        }, "updater").start();
    }
}
