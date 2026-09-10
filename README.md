# Screen Mirror Android

Xiaomi/Android端末からMediaProjection + WebRTCで画面を配信するアプリです。

## 必要なもの

- Android Studio
- JDK 17
- インターネット接続
- 先に用意したRender側の `server.js` / `Browser.html`

## server側

`server.js` が動いているURLをアプリのサーバーURLに入力します。


## ビルド

Android Studioでこのフォルダを開き、Gradle Sync後に
Build > Build APK(s) を実行してください。

生成されたAPKをXiaomiへ入れます。

## 使用

1. アプリを起動
2. RenderサーバーURLを入力
3. ルームコードを入力
4. 「画面共有開始」
5. Androidのシステム画面で共有を許可
6. 別端末で `Browser.html?mode=view&room=ROOMCODE` を開く

## 注意

- このプロジェクトはWebRTCのシグナリング形式を、前に作った `server.js` に合わせています。
- STUNはGoogle公開STUNを使用しています。
- TURNはまだ含めていません。NAT環境によってはTURNが必要です。
- WebRTC SDKはMaven Centralの `io.github.webrtc-sdk:android:150.7871.01` を使用します。
