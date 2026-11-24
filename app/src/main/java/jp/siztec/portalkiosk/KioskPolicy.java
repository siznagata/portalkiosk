package jp.siztec.portalkiosk;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
            dpm.addPersistentPreferredActivity(admin, homeFilter(), new ComponentName(activity, MainActivity.class));
        } catch (SecurityException ignored) {
            // 端末の制限で設定できない項目があっても、固定表示は続行する
        }
    }

    /**
     * 管理者がWi-Fiを設定するため、端末のWi-Fi設定画面を開く。
     * 設定アプリとホームボタンを、一時的にだけ許可する。アプリの画面に戻ると、apply() で元に戻る。
     *
     * @return 開けたとき true
     */
    static boolean openWifiSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        ResolveInfo resolved = activity.getPackageManager().resolveActivity(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved == null) {
            return false;
        }
        if (isDeviceOwner(activity)) {
            DevicePolicyManager dpm = dpm(activity);
            ComponentName admin = admin(activity);
            String settingsPackage = resolved.activityInfo.packageName;
            try {
                if ("com.android.settings".equals(settingsPackage)) {
                    dpm.setLockTaskPackages(admin, new String[] {activity.getPackageName(), settingsPackage});
                } else {
                    dpm.setLockTaskPackages(admin,
                            new String[] {activity.getPackageName(), settingsPackage, "com.android.settings"});
                }
                // 設定画面から戻れるように、ホームボタンだけ許可する（ホームはこのアプリ）
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_HOME);
            } catch (SecurityException e) {
                return false;
            }
        }
        try {
            activity.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 固定表示を一時的に解除する（設定の変更などを、端末の通常の操作で行うため）。
     * 接続先・PIN・デバイスオーナーはそのまま残す。このアプリの画面に戻ると、apply() と startLock() で
     * 元の固定表示に戻る。戻らないまま放置した場合も、MainActivity が一定時間後に戻す。
     *
     * @return 解除できたとき true
     */
    static boolean temporaryUnlock(Activity activity) {
        if (!isDeviceOwner(activity)) {
            return false;
        }
        DevicePolicyManager dpm = dpm(activity);
        ComponentName admin = admin(activity);
        try {
            // 解除中だけ、既定のホームを端末のホーム（ランチャー）に切り替える。
            // このアプリを開くと、apply() が既定のホームをこのアプリへ戻す。
            ComponentName launcher = findLauncher(activity);
            if (launcher != null) {
                dpm.addPersistentPreferredActivity(admin, homeFilter(), launcher);
            }
            stopLock(activity);
            dpm.setStatusBarDisabled(admin, false);
            if (launcher != null) {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.setComponent(launcher);
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(home);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static IntentFilter homeFilter() {
        IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addCategory(Intent.CATEGORY_DEFAULT);
        return home;
    }

    /** このアプリ以外の、端末のホーム（ランチャー）を探す。見つからないときは null。 */
    private static ComponentName findLauncher(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        String self = activity.getPackageName();
        for (ResolveInfo info : activity.getPackageManager().queryIntentActivities(intent, 0)) {
            if (!self.equals(info.activityInfo.packageName)) {
                return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
            }
        }
        return null;
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
