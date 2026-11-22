package lt.fotoekranas.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Nuotraukų ir nustatymų saugykla televizoriaus atmintyje. */
public class Storage {
    private final File dir;
    private final File logo;
    private final File tmpDir;
    private final SharedPreferences prefs;
    private final Random random = new Random();

    public Storage(Context c) {
        dir = new File(c.getFilesDir(), "photos");
        tmpDir = new File(c.getFilesDir(), "tmp");
        logo = new File(c.getFilesDir(), "logo.png");
        dir.mkdirs();
        tmpDir.mkdirs();
        prefs = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        if (!prefs.contains("pin")) {
            prefs.edit().putString("pin", String.valueOf(1000 + random.nextInt(9000))).apply();
        }
    }

    public static boolean validName(String n) {
        return n != null && n.matches("[A-Za-z0-9_\\-]+\\.jpg");
    }

    public synchronized List<File> listPhotos() {
        File[] fs = dir.listFiles();
        List<File> out = new ArrayList<>();
        if (fs == null) return out;
        Arrays.sort(fs);
        for (File f : fs) {
            if (validName(f.getName())) out.add(f);
        }
        return out;
    }

    public synchronized File photo(String name) {
        if (!validName(name)) return null;
        File f = new File(dir, name);
        return f.exists() ? f : null;
    }

    public String savePhoto(InputStream in, long len) throws IOException {
        String name;
        synchronized (this) {
            name = System.currentTimeMillis() + "_" + (1000 + random.nextInt(9000)) + ".jpg";
        }
        File tmp = new File(tmpDir, name);
        copy(in, len, tmp);
        synchronized (this) {
            if (!tmp.renameTo(new File(dir, name))) {
                tmp.delete();
                throw new IOException("rename failed");
            }
        }
        return name;
    }

    public void saveLogo(InputStream in, long len) throws IOException {
        File tmp = new File(tmpDir, "logo.tmp");
        copy(in, len, tmp);
        synchronized (this) {
            logo.delete();
            if (!tmp.renameTo(logo)) {
                tmp.delete();
                throw new IOException("rename failed");
            }
        }
    }

    private static void copy(InputStream in, long len, File target) throws IOException {
        FileOutputStream out = new FileOutputStream(target);
        try {
            byte[] buf = new byte[64 * 1024];
            long left = len;
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) throw new IOException("unexpected end");
                out.write(buf, 0, r);
                left -= r;
            }
        } catch (IOException e) {
            out.close();
            target.delete();
            throw e;
        }
        out.close();
    }

    public synchronized boolean deletePhoto(String name) {
        File f = photo(name);
        return f != null && f.delete();
    }

    public synchronized void clearPhotos() {
        for (File f : listPhotos()) f.delete();
    }

    public synchronized File logo() {
        return logo.exists() ? logo : null;
    }

    public synchronized void deleteLogo() {
        logo.delete();
    }

    public int interval() {
        return prefs.getInt("interval", 8);
    }

    public void setInterval(int s) {
        if (s < 2) s = 2;
        if (s > 600) s = 600;
        prefs.edit().putInt("interval", s).apply();
    }

    public String pin() {
        return prefs.getString("pin", "0000");
    }
}
