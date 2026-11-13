package jp.siztec.portalkiosk;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserManager;
import android.provider.Settings;

/**
 * デバイスオーナーとして端末を専用端末（キオスク）にする設定。
 * デバイスオーナーでないとき（開発中の確認など）は何もしない。
 */
final class KioskPolicy {
    private KioskPolicy() {
    }

    private static DevicePolicyManager dpm(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    private static ComponentName admin(Context context) {
        return new ComponentName(context, AdminReceiver.class);
    }

    static boolean isDeviceOwner(Context context) {
        return dpm(context).isDeviceOwnerApp(context.getPackageName());
    }

    /** 固定表示・起動時の自動起動・カメラ許可などを設定する。起動のたびに呼んでよい（冪等）。 */
    static void apply(Activity activity) {
        if (!isDeviceOwner(activity)) {
            return;
        }
        DevicePolicyManager dpm = dpm(activity);
        ComponentName admin = admin(activity);
        String pkg = activity.getPackageName();
        try {
            dpm.setLockTaskPackages(admin, new String[] {pkg});
            // 0: ホーム・最近使ったアプリ・通知・ステータスバー・電源メニュー等をすべて出さない
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            dpm.setStatusBarDisabled(admin, true);
            // 画面ロックが設定されていない端末のみ有効（共有端末は画面ロックなしで運用する）
            dpm.setKeyguardDisabled(admin, true);
            dpm.setPermissionGrantState(admin, pkg, Manifest.permission.CAMERA,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            // 給電中は画面を消さない（AC・USB・ワイヤレス）
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7");
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);
            dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER);

            // ホームボタン・再起動後に、このアプリを標準のホームとして起動する
            IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addCategory(Intent.CATEGORY_DEFAULT);
            dpm.addPersistentPreferredActivity(admin, home, new ComponentName(activity, MainActivity.class));
        } catch (SecurityException ignored) {
            // 端末の制限で設定できない項目があっても、固定表示は続行する
        }
    }

    /** 固定表示を開始する（デバイスオーナーで許可済みのときだけ有効）。 */
    static void startLock(Activity activity) {
        if (!isDeviceOwner(activity)) {
            return;
        }
        try {
            activity.startLockTask();
        } catch (IllegalStateException | SecurityException ignored) {
            // 既に固定中など
        }
    }

    static void stopLock(Activity activity) {
        try {
            activity.stopLockTask();
        } catch (IllegalStateException | SecurityException ignored) {
            // 固定中でないとき
        }
    }

    /** 設定を元に戻し、デバイスオーナーを解除する。 */
    static void release(Activity activity) {
        if (!isDeviceOwner(activity)) {
            return;
        }
        DevicePolicyManager dpm = dpm(activity);
        ComponentName admin = admin(activity);
        String pkg = activity.getPackageName();
        stopLock(activity);
        try {
            dpm.clearPackagePersistentPreferredActivities(admin, pkg);
            dpm.setStatusBarDisabled(admin, false);
            dpm.setKeyguardDisabled(admin, false);
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "0");
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADD_USER);
            dpm.setLockTaskPackages(admin, new String[0]);
        } catch (SecurityException ignored) {
            // 解除は続行する
        }
        dpm.clearDeviceOwnerApp(pkg);
    }
}
