package com.shirasu.screenmirror;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_MEDIA_PROJECTION = 1001;

    private EditText serverUrl;
    private EditText roomCode;
    private Button startButton;
    private Button stopButton;
    private TextView status;

    private final ActivityResultLauncher<Intent> projectionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                            Intent serviceIntent = new Intent(this, MirrorService.class);
                            serviceIntent.setAction(MirrorService.ACTION_START);
                            serviceIntent.putExtra(MirrorService.EXTRA_RESULT_CODE, result.getResultCode());
                            serviceIntent.putExtra(MirrorService.EXTRA_PROJECTION_DATA, result.getData());
                            serviceIntent.putExtra(MirrorService.EXTRA_SERVER_URL, serverUrl.getText().toString().trim());
                            serviceIntent.putExtra(MirrorService.EXTRA_ROOM, roomCode.getText().toString().trim().toUpperCase());

                            androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent);

                            startButton.setEnabled(false);
                            stopButton.setEnabled(true);
                            status.setText("画面共有を開始しました。配信端末を待っています...");
                        } else {
                            status.setText("画面共有の許可がキャンセルされました。");
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serverUrl = findViewById(R.id.serverUrl);
        roomCode = findViewById(R.id.roomCode);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        status = findViewById(R.id.status);

        // 画像の環境に合わせた初期値。必要なら画面上で変更できます。
        serverUrl.setText("https://lu1221.onrender.com");

        startButton.setOnClickListener(v -> requestProjection());
        stopButton.setOnClickListener(v -> stopMirror());

        if (savedInstanceState != null) {
            boolean running = savedInstanceState.getBoolean("running", false);
            startButton.setEnabled(!running);
            stopButton.setEnabled(running);
        }
    }

    private void requestProjection() {
        String url = serverUrl.getText().toString().trim();
        String room = roomCode.getText().toString().trim();

        if (url.isEmpty()) {
            status.setText("RenderサーバーURLを入力してください。");
            return;
        }

        if (room.isEmpty()) {
            status.setText("ルームコードを入力してください。");
            return;
        }

        android.media.projection.MediaProjectionManager manager =
                (android.media.projection.MediaProjectionManager)
                        getSystemService(MEDIA_PROJECTION_SERVICE);

        projectionLauncher.launch(manager.createScreenCaptureIntent());
    }

    private void stopMirror() {
        Intent intent = new Intent(this, MirrorService.class);
        intent.setAction(MirrorService.ACTION_STOP);
        startService(intent);

        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        status.setText("配信を停止しました。");
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("running", !startButton.isEnabled());
        super.onSaveInstanceState(outState);
    }
}
