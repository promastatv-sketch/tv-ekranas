package lt.fotoekranas.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Laiko serverį gyvą fone ir, televizoriui pabudus iš budėjimo režimo,
 * vėl atidaro skaidres.
 */
public class KeepAliveService extends Service {
    private static final String CHANNEL = "fotoekranas";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, Intent intent) {
            // Mažas uždelsimas, kad TV spėtų atsibusti.
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Shared.launchSlides(KeepAliveService.this);
                }
            }, 1500);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Shared.init(this);
        goForeground();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(wakeReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(wakeReceiver, f);
        }
    }

    private void goForeground() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Foto ekranas", NotificationManager.IMPORTANCE_MIN);
            if (nm != null) nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b.setContentTitle("Foto ekranas")
                .setContentText("Skaidrės veikia")
                .setSmallIcon(R.drawable.icon)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, n);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(wakeReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
