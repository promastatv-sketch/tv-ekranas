package lt.fotoekranas.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Paleidžia skaidres ir serverį įjungus televizorių. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Shared.startService(context);
        Shared.launchSlides(context);
    }
}
