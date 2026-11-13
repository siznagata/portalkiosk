package jp.siztec.portalkiosk;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub Releases からの自動更新。
 * 最新リリースの「portalkiosk-<versionCode>.apk」と、その SHA-256 の「….apk.sha256」を取得し、
 * ハッシュが一致したときだけ、PackageInstaller でインストールする（デバイスオーナーのとき、確認画面は出ない）。
 * 署名が異なる APK は、Android が更新として受け付けない。
 */
final class Updater {
    static final String ACTION_RESULT = "jp.siztec.portalkiosk.UPDATE_RESULT";

    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern APK_NAME = Pattern.compile("^portalkiosk-(\\d+)\\.apk$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final long MAX_APK_BYTES = 100L * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;

    /** ハッシュ検証済みの更新用APK。 */
    static final class Staged {
        final File apk;
        final int versionCode;

        Staged(File apk, int versionCode) {
            this.apk = apk;
            this.versionCode = versionCode;
        }
    }

    private Updater() {
    }

    static boolean isEnabled() {
        return REPO.matcher(BuildConfig.UPDATE_REPO).matches();
    }

    /** 新しいバージョンがあればダウンロードして検証し、なければ null を返す。 */
    static Staged fetch(Context context) throws IOException, JSONException {
        if (!isEnabled()) {
            return null;
        }
        String repo = BuildConfig.UPDATE_REPO;
        String latest = new String(readAll("https://api.github.com/repos/" + repo + "/releases/latest", 1024 * 1024),
                "UTF-8");
        JSONArray assets = new JSONObject(latest).getJSONArray("assets");

        int newVersion = 0;
        String apkUrl = null;
        String apkName = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            Matcher m = APK_NAME.matcher(asset.getString("name"));
            if (m.matches()) {
                int v = Integer.parseInt(m.group(1));
                if (v > newVersion) {
                    newVersion = v;
                    apkName = asset.getString("name");
                    apkUrl = asset.getString("browser_download_url");
                }
            }
        }
        if (apkUrl == null || newVersion <= currentVersionCode(context)) {
            return null;
        }
        String shaUrl = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if ((apkName + ".sha256").equals(asset.getString("name"))) {
                shaUrl = asset.getString("browser_download_url");
            }
        }
        if (shaUrl == null) {
            return null; // ハッシュがないリリースは使わない
        }
        String prefix = "https://github.com/" + repo + "/releases/download/";
        if (!apkUrl.startsWith(prefix) || !shaUrl.startsWith(prefix)) {
            throw new IOException("想定外のダウンロード元です");
        }

        String shaText = new String(readAll(shaUrl, 4096), "UTF-8").trim();
        String expected = shaText.split("\\s+")[0];
        if (!SHA256.matcher(expected).matches()) {
            throw new IOException("ハッシュの形式が正しくありません");
        }

        File dir = new File(context.getCacheDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("保存先を作成できません");
        }
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                f.delete();
            }
        }
        File apk = new File(dir, apkName);
        String actual = download(apkUrl, apk);
        if (!actual.equalsIgnoreCase(expected)) {
            apk.delete();
            throw new IOException("ハッシュが一致しません");
        }
        return new Staged(apk, newVersion);
    }

    static int currentVersionCode(Context context) {
        try {
            return (int) context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** 検証済みのAPKをインストールする。成功するとアプリは更新のため終了する。 */
    static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream out = session.openWrite("update.apk", 0, apk.length());
                    InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }
            Intent intent = new Intent(context, UpdateResultReceiver.class).setAction(ACTION_RESULT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(context, id, intent, flags);
            session.commit(pending.getIntentSender());
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "portalkiosk-updater");
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
            int code = c.getResponseCode();
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        if (!"https".equalsIgnoreCase(c.getURL().getProtocol())) {
            c.disconnect();
            throw new IOException("https以外へ移動しました");
        }
        return c;
    }

    private static byte[] readAll(String url, int maxBytes) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) {
                    throw new IOException("応答が大きすぎます");
                }
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** ファイルへ保存し、SHA-256（16進）を返す。 */
    private static String download(String url, File target) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(target)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_APK_BYTES) {
                    throw new IOException("ファイルが大きすぎます");
                }
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            c.disconnect();
        }
    }
}
