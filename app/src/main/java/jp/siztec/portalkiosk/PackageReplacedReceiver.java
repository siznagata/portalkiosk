package jp.siztec.portalkiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** アプリの更新後に、専用表示を起動し直す。 */
public class PackageReplacedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            return;
        }
        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
    }
}
