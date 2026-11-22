package lt.fotoekranas.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Skaidrių rodymas per visą ekraną. */
public class MainActivity extends Activity implements PhotoServer.Listener {

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ImageView[] slides = new ImageView[2];
    private int front = 0;
    private ImageView logoView;

    private LinearLayout emptyPanel;
    private ImageView emptyQr;
    private TextView emptyText;

    private LinearLayout infoPanel;
    private ImageView infoQr;
    private TextView infoText;

    private LinearLayout menuPanel;

    private List<File> photos = new ArrayList<>();
    private int index = -1;
    private boolean showing = false;
    private String lastUrl = null;
    private long logoStamp = -1;
    private boolean onBreak = false;

    private final Runnable nextRunnable = new Runnable() {
        @Override
        public void run() {
            advance();
        }
    };

    private final Runnable hideInfoRunnable = new Runnable() {
        @Override
        public void run() {
            infoPanel.setVisibility(View.GONE);
        }
    };

    private final Runnable networkCheck = new Runnable() {
        @Override
        public void run() {
            updateAddress();
            handler.postDelayed(this, 20000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        Shared.init(this);
        Shared.startService(this);

        buildUi();
    }

    // ---------- Vartotojo sąsaja ----------

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        for (int i = 0; i < 2; i++) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setAlpha(0f);
            root.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            slides[i] = iv;
        }

        logoView = new ImageView(this);
        logoView.setScaleType(ImageView.ScaleType.FIT_END);
        logoView.setAdjustViewBounds(true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(200), dp(90), Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(24), dp(32), 0);
        root.addView(logoView, lp);

        // Tuščias ekranas (kai nuotraukų dar nėra)
        emptyPanel = new LinearLayout(this);
        emptyPanel.setOrientation(LinearLayout.HORIZONTAL);
        emptyPanel.setGravity(Gravity.CENTER);
        emptyQr = new ImageView(this);
        emptyQr.setBackgroundColor(Color.WHITE);
        emptyQr.setPadding(dp(10), dp(10), dp(10), dp(10));
        emptyPanel.addView(emptyQr, new LinearLayout.LayoutParams(dp(260), dp(260)));
        emptyText = new TextView(this);
        emptyText.setTextColor(Color.WHITE);
        emptyText.setTextSize(22);
        emptyText.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(dp(40), 0, 0, 0);
        emptyPanel.addView(emptyText, tlp);
        root.addView(emptyPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Informacijos juosta (atsiranda paspaudus OK)
        infoPanel = new LinearLayout(this);
        infoPanel.setOrientation(LinearLayout.HORIZONTAL);
        infoPanel.setGravity(Gravity.CENTER_VERTICAL);
        infoPanel.setBackgroundColor(0xDD000000);
        infoPanel.setPadding(dp(16), dp(16), dp(24), dp(16));
        infoQr = new ImageView(this);
        infoQr.setBackgroundColor(Color.WHITE);
        infoQr.setPadding(dp(6), dp(6), dp(6), dp(6));
        infoPanel.addView(infoQr, new LinearLayout.LayoutParams(dp(150), dp(150)));
        infoText = new TextView(this);
        infoText.setTextColor(Color.WHITE);
        infoText.setTextSize(16);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(dp(20), 0, 0, 0);
        infoPanel.addView(infoText, ilp);
        infoPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        plp.setMargins(dp(32), 0, 0, dp(32));
        root.addView(infoPanel, plp);

        // Meniu (atsiranda paspaudus Atgal)
        menuPanel = new LinearLayout(this);
        menuPanel.setOrientation(LinearLayout.VERTICAL);
        menuPanel.setGravity(Gravity.CENTER);
        menuPanel.setBackgroundColor(0xEE101010);
        menuPanel.setPadding(dp(32), dp(24), dp(32), dp(24));
        TextView mt = new TextView(this);
        mt.setText("Foto ekranas");
        mt.setTextColor(Color.WHITE);
        mt.setTextSize(22);
        mt.setTypeface(Typeface.DEFAULT_BOLD);
        mt.setGravity(Gravity.CENTER);
        menuPanel.addView(mt);
        addMenuButton("Grįžti į skaidres", new Runnable() {
            @Override
            public void run() {
                hideMenu();
            }
        });
        addMenuButton("Rodyti QR kodą telefonui", new Runnable() {
            @Override
            public void run() {
                hideMenu();
                showInfo(60000);
            }
        });
        addMenuButton("Leisti atsidaryti pabudus TV", new Runnable() {
            @Override
            public void run() {
                hideMenu();
                openOverlayPermission();
            }
        });
        addMenuButton("TV nustatymai", new Runnable() {
            @Override
            public void run() {
                hideMenu();
                try {
                    Intent i = new Intent(Settings.ACTION_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ignored) {
                }
            }
        });
        menuPanel.setVisibility(View.GONE);
        root.addView(menuPanel, new FrameLayout.LayoutParams(dp(460), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setContentView(root);
    }

    private void addMenuButton(String text, final Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setFocusable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, 0);
        menuPanel.addView(b, lp);
    }

    private void showMenu() {
        menuPanel.setVisibility(View.VISIBLE);
        if (menuPanel.getChildCount() > 1) menuPanel.getChildAt(1).requestFocus();
    }

    private void hideMenu() {
        menuPanel.setVisibility(View.GONE);
        hideSystemUi();
    }

    private void showInfo(long ms) {
        updateAddress();
        infoPanel.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideInfoRunnable);
        handler.postDelayed(hideInfoRunnable, ms);
    }

    private boolean overlayAllowed() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void openOverlayPermission() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        View d = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (Build.VERSION.SDK_INT >= 19) flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        d.setSystemUiVisibility(flags);
    }

    // ---------- Gyvavimo ciklas ----------

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        Shared.server.setListener(this);
        reload();
        handler.post(networkCheck);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(nextRunnable);
        handler.removeCallbacks(networkCheck);
        showing = false;
        if (Shared.server.getListener() == this) Shared.server.setListener(null);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void onPhotosChanged() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                reload();
            }
        });
    }

    // ---------- Pultelis ----------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (menuPanel.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                hideMenu();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                showMenu();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (infoPanel.getVisibility() == View.VISIBLE) infoPanel.setVisibility(View.GONE);
                else showInfo(30000);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                showStep(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                showStep(-1);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // ---------- Turinys ----------

    private void reload() {
        photos = Shared.storage.listPhotos();
        updateLogo();
        updateAddress();
        if (photos.isEmpty()) {
            handler.removeCallbacks(nextRunnable);
            showing = false;
            index = -1;
            for (ImageView iv : slides) {
                iv.animate().cancel();
                iv.setAlpha(0f);
                iv.setImageDrawable(null);
            }
            emptyPanel.setVisibility(View.VISIBLE);
        } else {
            emptyPanel.setVisibility(View.GONE);
            if (!showing) {
                showing = true;
                showStep(1);
            } else {
                // naujas intervalas ar sąrašas – tęsiame nuo dabartinės vietos
                handler.removeCallbacks(nextRunnable);
                handler.postDelayed(nextRunnable, (onBreak ? Shared.storage.breakSeconds() : Shared.storage.interval()) * 1000L);
            }
        }
    }

    /** Po kiekvienos nuotraukos parodoma tarpinė nuotrauka (jei įkelta). */
    private void advance() {
        File b = Shared.storage.breakImage();
        if (b != null && !onBreak && !photos.isEmpty()) {
            handler.removeCallbacks(nextRunnable);
            Bitmap bmp = decode(b);
            if (bmp != null) {
                display(bmp);
                onBreak = true;
                handler.postDelayed(nextRunnable, Shared.storage.breakSeconds() * 1000L);
                return;
            }
        }
        showStep(1);
    }

    private void showStep(int dir) {
        handler.removeCallbacks(nextRunnable);
        onBreak = false;
        if (photos.isEmpty()) return;
        int n = photos.size();
        index = ((index + dir) % n + n) % n;
        Bitmap bmp = decode(photos.get(index));
        if (bmp != null) display(bmp);
        handler.postDelayed(nextRunnable, bmp == null ? 500 : Shared.storage.interval() * 1000L);
    }

    private void display(Bitmap bmp) {
        final ImageView back = slides[1 - front];
        final ImageView old = slides[front];
        back.animate().cancel();
        old.animate().cancel();
        back.setImageBitmap(bmp);
        back.bringToFront();
        logoView.bringToFront();
        emptyPanel.bringToFront();
        infoPanel.bringToFront();
        menuPanel.bringToFront();
        back.setAlpha(0f);
        back.animate().alpha(1f).setDuration(700).withEndAction(new Runnable() {
            @Override
            public void run() {
                old.setAlpha(0f);
                old.setImageDrawable(null);
            }
        }).start();
        front = 1 - front;
    }

    private Bitmap decode(File f) {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int maxW = Math.max(dm.widthPixels, 1280);
            int maxH = Math.max(dm.heightPixels, 720);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxW || o.outHeight / (sample * 2) >= maxH) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable t) {
            return null;
        }
    }

    private void updateLogo() {
        File lf = Shared.storage.logo();
        long stamp = lf == null ? 0 : lf.lastModified() + lf.length();
        if (stamp == logoStamp) return;
        logoStamp = stamp;
        if (lf == null) {
            logoView.setImageDrawable(null);
            logoView.setVisibility(View.GONE);
        } else {
            Bitmap b = BitmapFactory.decodeFile(lf.getAbsolutePath());
            logoView.setImageBitmap(b);
            logoView.setVisibility(b == null ? View.GONE : View.VISIBLE);
        }
    }

    private void updateAddress() {
        String ip = findIp();
        String pin = Shared.storage.pin();
        String url = ip == null ? null : "http://" + ip + ":" + Shared.PORT + "/?pin=" + pin;
        String hint = overlayAllowed() ? "" :
                "\n\nKad skaidrės atsidarytų pačios pabudus TV: spauskite pultelio „Atgal“ → „Leisti atsidaryti pabudus TV“.";

        if (url == null) {
            emptyQr.setVisibility(View.GONE);
            emptyText.setText("Televizorius neprisijungęs prie Wi-Fi tinklo.\n\nPrijunkite jį prie Wi-Fi TV nustatymuose." + hint);
            infoQr.setVisibility(View.GONE);
            infoText.setText("Nėra Wi-Fi ryšio");
            lastUrl = null;
            return;
        }
        String plain = "http://" + ip + ":" + Shared.PORT;
        emptyText.setText("Nuskenuokite QR kodą telefonu ir įkelkite nuotraukas.\n\n"
                + "Telefonas turi būti tame pačiame Wi-Fi tinkle.\n\n"
                + "Arba naršyklėje įveskite:\n" + plain + "\nPIN: " + pin + hint);
        infoText.setText("Įkelti nuotraukas:\n" + plain + "\nPIN: " + pin
                + "\n\n◀ ▶  ankstesnė / kita\nAtgal – meniu");
        if (!url.equals(lastUrl)) {
            lastUrl = url;
            Bitmap q = qr(url, 400);
            emptyQr.setImageBitmap(q);
            infoQr.setImageBitmap(q);
        }
        emptyQr.setVisibility(View.VISIBLE);
        infoQr.setVisibility(View.VISIBLE);
    }

    private static Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth();
            int h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    private static String findIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String name = ni.getName();
                        if (name.startsWith("wlan") || name.startsWith("eth")) return a.getHostAddress();
                        if (fallback == null) fallback = a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
