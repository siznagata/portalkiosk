package jp.siztec.portalkiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/** インストール結果の受け取り。成功時はアプリが更新のため終了するので、失敗の状態だけ記録する。 */
public class UpdateResultReceiver extends BroadcastReceiver {
    private static final String TAG = "PortalKiosk";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status != PackageInstaller.STATUS_SUCCESS) {
            // 確認画面が必要な状態（STATUS_PENDING_USER_ACTION）は、専用端末では開かない
            Log.w(TAG, "更新に失敗しました: status=" + status);
        }
    }
}
