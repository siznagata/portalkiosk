package jp.siztec.portalkiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 端末ごとの設定（接続先URL・管理者PIN）。
 * 値は端末の初回設定画面で入力し、ソースコードには持たない。PINはハッシュ化して保存し、ログへ出さない。
 */
final class Config {
    static final int MIN_PIN_LENGTH = 6;

    private static final String PREFS = "kiosk_config";
    private static final String KEY_URL = "url";
    private static final String KEY_SALT = "pin_salt";
    private static final String KEY_HASH = "pin_hash";
    private static final int ITERATIONS = 120000;
    private static final int KEY_BITS = 256;

    private Config() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isConfigured(Context context) {
        SharedPreferences p = prefs(context);
        return p.getString(KEY_URL, null) != null && p.getString(KEY_HASH, null) != null;
    }

    static String url(Context context) {
        return prefs(context).getString(KEY_URL, null);
    }

    /** 接続先として許可するホスト名（設定したURLのホストのみ）。 */
    static String allowedHost(Context context) {
        String url = url(context);
        if (url == null) {
            return null;
        }
        return Uri.parse(url).getHost();
    }

    /** https のURLだけを受け付ける。正しければ null、誤りなら理由を返す。 */
    static String validateUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "接続先のURLを入力してください。";
        }
        Uri uri = Uri.parse(url.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return "URLは https:// から始めてください。";
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            return "URLのホスト名が正しくありません。";
        }
        return null;
    }

    static String validatePin(String pin) {
        if (pin == null || pin.length() < MIN_PIN_LENGTH) {
            return "管理者PINは" + MIN_PIN_LENGTH + "桁以上で入力してください。";
        }
        for (int i = 0; i < pin.length(); i++) {
            if (!Character.isDigit(pin.charAt(i))) {
                return "管理者PINは数字だけで入力してください。";
            }
        }
        return null;
    }

    static void save(Context context, String url, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        prefs(context).edit()
                .putString(KEY_URL, url.trim())
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_HASH, Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
                .apply();
    }

    static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }

    static boolean verifyPin(Context context, String pin) {
        SharedPreferences p = prefs(context);
        String saltText = p.getString(KEY_SALT, null);
        String hashText = p.getString(KEY_HASH, null);
        if (saltText == null || hashText == null || pin == null) {
            return false;
        }
        byte[] expected = Base64.decode(hashText, Base64.NO_WRAP);
        byte[] actual = hash(pin, Base64.decode(saltText, Base64.NO_WRAP));
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] hash(String pin, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PINのハッシュ化に失敗しました", e);
        }
    }
}
