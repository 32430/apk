package com.shirasu.screenmirror;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private EditText serverUrl;
    private EditText roomCode;
    private Button startButton;
    private Button stopButton;
    private TextView status;

    private final ActivityResultLauncher<Intent> projectionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {

                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null) {

                            Intent serviceIntent =
                                    new Intent(this, MirrorService.class);

                            serviceIntent.setAction(
                                    MirrorService.ACTION_START
                            );

                            serviceIntent.putExtra(
                                    MirrorService.EXTRA_RESULT_CODE,
                                    result.getResultCode()
                            );

                            serviceIntent.putExtra(
                                    MirrorService.EXTRA_PROJECTION_DATA,
                                    result.getData()
                            );

                            serviceIntent.putExtra(
                                    MirrorService.EXTRA_SERVER_URL,
                                    serverUrl.getText()
                                            .toString()
                                            .trim()
                            );

                            serviceIntent.putExtra(
                                    MirrorService.EXTRA_ROOM,
                                    roomCode.getText()
                                            .toString()
                                            .trim()
                                            .toUpperCase()
                            );

                            try {
                                androidx.core.content.ContextCompat
                                        .startForegroundService(
                                                this,
                                                serviceIntent
                                        );

                                startButton.setEnabled(false);
                                stopButton.setEnabled(true);

                                status.setText(
                                        "画面共有を開始しています..."
                                );

                            } catch (Throwable e) {

                                showError(
                                        "Foreground Service開始エラー",
                                        e
                                );
                            }

                        } else {

                            status.setText(
                                    "画面共有の許可がキャンセルされました。"
                            );
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        serverUrl = findViewById(R.id.serverUrl);
        roomCode = findViewById(R.id.roomCode);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        status = findViewById(R.id.status);

        serverUrl.setText(
                "https://lu1221.onrender.com"
        );

        startButton.setOnClickListener(
                v -> requestProjection()
        );

        stopButton.setOnClickListener(
                v -> stopMirror()
        );

        /*
         * 前回のクラッシュ・例外情報を確認
         */
        SharedPreferences prefs =
                getSharedPreferences(
                        "debug",
                        MODE_PRIVATE
                );

        String previousError =
                prefs.getString(
                        "last_error",
                        null
                );

        if (previousError != null
                && !previousError.isEmpty()) {

            status.setText(
                    "前回の画面共有エラー:\n\n"
                            + previousError
            );

            prefs.edit()
                    .remove("last_error")
                    .apply();
        }

        if (savedInstanceState != null) {

            boolean running =
                    savedInstanceState.getBoolean(
                            "running",
                            false
                    );

            startButton.setEnabled(!running);
            stopButton.setEnabled(running);
        }
    }

    private void requestProjection() {

        String url =
                serverUrl.getText()
                        .toString()
                        .trim();

        String room =
                roomCode.getText()
                        .toString()
                        .trim();

        if (url.isEmpty()) {

            status.setText(
                    "RenderサーバーURLを入力してください。"
            );

            return;
        }

        if (room.isEmpty()) {

            status.setText(
                    "ルームコードを入力してください。"
            );

            return;
        }

        android.media.projection.MediaProjectionManager manager =
                (android.media.projection.MediaProjectionManager)
                        getSystemService(
                                MEDIA_PROJECTION_SERVICE
                        );

        if (manager == null) {

            status.setText(
                    "MediaProjectionManagerを取得できませんでした。"
            );

            return;
        }

        try {

            Intent captureIntent =
                    manager.createScreenCaptureIntent();

            projectionLauncher.launch(
                    captureIntent
            );

        } catch (Throwable e) {

            showError(
                    "画面共有許可画面の起動エラー",
                    e
            );
        }
    }

    private void stopMirror() {

        try {

            Intent intent =
                    new Intent(
                            this,
                            MirrorService.class
                    );

            intent.setAction(
                    MirrorService.ACTION_STOP
            );

            startService(intent);

            startButton.setEnabled(true);
            stopButton.setEnabled(false);

            status.setText(
                    "配信を停止しました。"
            );

        } catch (Throwable e) {

            showError(
                    "配信停止エラー",
                    e
            );
        }
    }

    private void showError(
            String title,
            Throwable e
    ) {

        String message =
                e.getClass().getName()
                        + "\n\n"
                        + String.valueOf(
                                e.getMessage()
                        );

        status.setText(
                title
                        + "\n\n"
                        + message
        );
    }

    @Override
    protected void onSaveInstanceState(
            Bundle outState
    ) {

        outState.putBoolean(
                "running",
                !startButton.isEnabled()
        );

        super.onSaveInstanceState(
                outState
        );
    }
}
