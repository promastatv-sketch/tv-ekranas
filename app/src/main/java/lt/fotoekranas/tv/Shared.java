package lt.fotoekranas.tv;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Bendri objektai: saugykla ir serveris, gyvuojantys visą programėlės laiką. */
final class Shared {
    static final int PORT = 8080;
    static Storage storage;
    static PhotoServer server;

    private Shared() {
    }

    static synchronized void init(Context c) {
        Context app = c.getApplicationContext();
        if (storage == null) storage = new Storage(app);
        if (server == null) {
            server = new PhotoServer(PORT, storage, app.getAssets());
            server.start();
        }
    }

    static void startService(Context c) {
        try {
            Intent s = new Intent(c, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
        } catch (Exception ignored) {
        }
    }

    static void launchSlides(Context c) {
        try {
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
