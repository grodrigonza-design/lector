package cr.portarretratos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Abre el portarretratos solo cuando la tablet se enciende. */
public class Arranque extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Intent abrir = new Intent(c, MainActivity.class);
        abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(abrir);
    }
}
