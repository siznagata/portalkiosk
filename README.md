# portalkiosk

社員ポータルの共有端末（Android）を、ポータルの専用URLだけを開く専用端末（キオスク）にするアプリ。

- デバイスオーナー＋ロックタスクモードで固定し、再起動後も自動で固定表示に戻る。
- 接続先URLと管理者PINは、端末の初期設定画面で入力する。ソースコードには含まない。
- カメラは、接続先のページにだけ許可する（マイクは許可しない）。
- GitHub Releases の最新版を確認し、ハッシュが一致したときだけ自動で更新する。

導入・更新の手順は、ポータル本体の運用手順書（共有端末の設置）を参照する。

## ビルド

JDK 17以上と Android SDK（プラットフォーム35）が必要。

```
gradlew assembleDebug
```

## リリース

署名鍵を Secrets（`KIOSK_KEYSTORE_BASE64`、`KIOSK_KEYSTORE_PASSWORD`、`KIOSK_KEY_ALIAS`、`KIOSK_KEY_PASSWORD`）に登録し、タグ（例：`v1.0`）を push すると、署名済みのAPKとSHA-256が Releases に公開される。
