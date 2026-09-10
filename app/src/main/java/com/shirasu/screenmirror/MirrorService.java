:::writing{variant="document" id="58321" title="修正版 MirrorService.java"}
package com.shirasu.screenmirror;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MirrorService extends Service {

    public static final String ACTION_START =
            "com.shirasu.screenmirror.START";

    public static final String ACTION_STOP =
            "com.shirasu.screenmirror.STOP";

    public static final String EXTRA_RESULT_CODE =
            "resultCode";

    public static final String EXTRA_PROJECTION_DATA =
            "projectionData";

    public static final String EXTRA_SERVER_URL =
            "serverUrl";

    public static final String EXTRA_ROOM =
            "room";

    private static final String TAG =
            "ScreenMirror";

    private static final String CHANNEL_ID =
            "screen_mirror";

    private static final int NOTIFICATION_ID =
            77;

    /*
     * デバッグ情報
     */
    private static final String DEBUG_PREFS =
            "debug";

    private static final String KEY_STAGE =
            "last_stage";

    /*
     * 前回セッションが実行中だったか。
     *
     * setStage()では変更しない。
     */
    private static final String KEY_SESSION_ACTIVE =
            "session_active";

    /*
     * 正常停止したか。
     */
    private static final String KEY_CLEAN_STOP =
            "clean_stop";

    /*
     * 前回接続情報
     */
    private static final String KEY_SERVER_URL =
            "server_url";

    private static final String KEY_ROOM =
            "room";

    private OkHttpClient httpClient;
    private WebSocket socket;

    private PeerConnectionFactory factory;
    private EglBase eglBase;
    private SurfaceTextureHelper surfaceTextureHelper;

    private VideoSource videoSource;
    private VideoTrack screenTrack;
    private VideoCapturer capturer;

    private String room;
    private String serverUrl;
    private String clientId;

    /*
     * stopMirror() が複数回呼ばれても
     * 後続処理が暴れないようにする。
     */
    private volatile boolean stopping = false;

    private final Map<String, PeerConnection> peers =
            new HashMap<>();

    // ============================================================
    // Service lifecycle
    // ============================================================

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        /*
         * 前回セッションが異常終了していないか確認。
         *
         * Native crash / Process death の場合、
         * onDestroy() が呼ばれない可能性がある。
         */
        SharedPreferences prefs =
                getSharedPreferences(
                        DEBUG_PREFS,
                        MODE_PRIVATE
                );

        boolean previousSessionActive =
                prefs.getBoolean(
                        KEY_SESSION_ACTIVE,
                        false
                );

        boolean previousCleanStop =
                prefs.getBoolean(
                        KEY_CLEAN_STOP,
                        true
                );

        String previousStage =
                prefs.getString(
                        KEY_STAGE,
                        "unknown"
                );

        Log.i(
                TAG,
                "Previous session state: active="
                        + previousSessionActive
                        + ", cleanStop="
                        + previousCleanStop
                        + ", stage="
                        + previousStage
        );

        /*
         * 本当に「前回セッションが途中で消えた」
         * 場合だけ異常終了として扱う。
         */
        if (previousSessionActive
                && !previousCleanStop) {

            Log.e(
                    TAG,
                    "Previous session ended unexpectedly. stage="
                            + previousStage
            );

            String previousServerUrl =
                    prefs.getString(
                            KEY_SERVER_URL,
                            ""
                    );

            String previousRoom =
                    prefs.getString(
                            KEY_ROOM,
                            ""
                    );

            /*
             * 今回の起動前に前回状態をリセット。
             */
            prefs.edit()
                    .putBoolean(
                            KEY_SESSION_ACTIVE,
                            false
                    )
                    .putBoolean(
                            KEY_CLEAN_STOP,
                            true
                    )
                    .apply();

            /*
             * 前回プロセス死亡のレポート。
             *
             * これは「Native crashだった」と
             * 断定するものではない。
             */
            sendCrashReport(
                    previousServerUrl,
                    previousRoom,
                    "previous_native_crash_or_process_death",
                    null,
                    previousStage
            );
        }

        /*
         * 今回のService生成。
         *
         * ここではセッション開始扱いにしない。
         */
        setStage(
                "service_created"
        );
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        if (intent == null) {
            return START_NOT_STICKY;
        }

        // ========================================================
        // STOP
        // ========================================================

        if (ACTION_STOP.equals(
                intent.getAction()
        )) {

            Log.i(
                    TAG,
                    "Stop requested"
            );

            setStage(
                    "stop_requested"
            );

            /*
             * 明示的な停止なので、
             * 先に正常終了フラグを立てる。
             */
            markCleanStop();

            stopMirror();

            stopSelf();

            return START_NOT_STICKY;
        }

        // ========================================================
        // START
        // ========================================================

        if (ACTION_START.equals(
                intent.getAction()
        )) {

            stopping = false;

            setStage(
                    "start_requested"
            );

            int resultCode =
                    intent.getIntExtra(
                            EXTRA_RESULT_CODE,
                            -1
                    );

            Intent projectionData =
                    getProjectionData(intent);

            serverUrl =
                    intent.getStringExtra(
                            EXTRA_SERVER_URL
                    );

            room =
                    intent.getStringExtra(
                            EXTRA_ROOM
                    );

            /*
             * 入力値確認。
             */
            if (projectionData == null
                    || serverUrl == null
                    || room == null
                    || resultCode != Activity.RESULT_OK) {

                String error =
                        "画面共有データが不正です。\n"
                                + "resultCode="
                                + resultCode;

                Log.e(
                        TAG,
                        error
                );

                saveError(error);

                setStage(
                        "invalid_projection_data"
                );

                sendCrashReport(
                        serverUrl,
                        room,
                        "invalid_projection_data",
                        null,
                        "start_requested"
                );

                markCleanStop();

                stopSelf();

                return START_NOT_STICKY;
            }

            /*
             * ここで初めて「実際のセッション開始」。
             */
            SharedPreferences prefs =
                    getSharedPreferences(
                            DEBUG_PREFS,
                            MODE_PRIVATE
                    );

            prefs.edit()
                    .putString(
                            KEY_SERVER_URL,
                            serverUrl
                    )
                    .putString(
                            KEY_ROOM,
                            room
                    )
                    .putBoolean(
                            KEY_SESSION_ACTIVE,
                            true
                    )
                    .putBoolean(
                            KEY_CLEAN_STOP,
                            false
                    )
                    .apply();

            Log.i(
                    TAG,
                    "Session marked active"
            );

            // ====================================================
            // Foreground Service
            // ====================================================

            try {

                setStage(
                        "foreground_start:start"
                );

                if (Build.VERSION.SDK_INT >= 29) {

                    startForeground(
                            NOTIFICATION_ID,
                            buildNotification(
                                    "画面共有を準備中..."
                            ),
                            ServiceInfo
                                    .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    );

                } else {

                    startForeground(
                            NOTIFICATION_ID,
                            buildNotification(
                                    "画面共有を準備中..."
                            )
                    );
                }

                setStage(
                        "foreground_start:success"
                );

            } catch (Throwable e) {

                Log.e(
                        TAG,
                        "startForeground failed",
                        e
                );

                saveError(
                        "Foreground Service開始エラー",
                        e
                );

                sendCrashReport(
                        serverUrl,
                        room,
                        "foreground_start_failed",
                        e,
                        "foreground_start:start"
                );

                markCleanStop();

                stopSelf();

                return START_NOT_STICKY;
            }

            // ====================================================
            // MediaProjection + WebRTC
            // ====================================================

            startCapture(
                    resultCode,
                    projectionData
            );
        }

        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent getProjectionData(
            Intent intent
    ) {

        if (Build.VERSION.SDK_INT >= 33) {

            return intent.getParcelableExtra(
                    EXTRA_PROJECTION_DATA,
                    Intent.class
            );

        } else {

            return intent.getParcelableExtra(
                    EXTRA_PROJECTION_DATA
            );
        }
    }

    // ============================================================
    // Capture
    // ============================================================

    private void startCapture(
            int resultCode,
            Intent projectionData
    ) {

        try {

            setStage(
                    "webrtc_initialize:start"
            );

            // ====================================================
            // WebRTC initialization
            // ====================================================

            PeerConnectionFactory.initialize(
                    PeerConnectionFactory
                            .InitializationOptions
                            .builder(
                                    getApplicationContext()
                            )
                            .setEnableInternalTracer(
                                    false
                            )
                            .createInitializationOptions()
            );

            setStage(
                    "webrtc_initialize:success"
            );

            // ====================================================
            // EGL
            // ====================================================

            setStage(
                    "egl_create:start"
            );

            eglBase =
                    EglBase.create();

            setStage(
                    "egl_create:success"
            );

            // ====================================================
            // Encoder / Decoder
            // ====================================================

            PeerConnectionFactory.Options options =
                    new PeerConnectionFactory.Options();

            DefaultVideoEncoderFactory
                    encoderFactory =
                    new DefaultVideoEncoderFactory(
                            eglBase.getEglBaseContext(),
                            true,
                            false
                    );

            DefaultVideoDecoderFactory
                    decoderFactory =
                    new DefaultVideoDecoderFactory(
                            eglBase.getEglBaseContext()
                    );

            setStage(
                    "codec_factory:created"
            );

            // ====================================================
            // PeerConnectionFactory
            // ====================================================

            factory =
                    PeerConnectionFactory
                            .builder()
                            .setOptions(options)
                            .setVideoEncoderFactory(
                                    encoderFactory
                            )
                            .setVideoDecoderFactory(
                                    decoderFactory
                            )
                            .createPeerConnectionFactory();

            if (factory == null) {

                throw new IllegalStateException(
                        "PeerConnectionFactory is null"
                );
            }

            setStage(
                    "peer_factory:success"
            );

            // ====================================================
            // VideoSource
            // ====================================================

            videoSource =
                    factory.createVideoSource(
                            false
                    );

            if (videoSource == null) {

                throw new IllegalStateException(
                        "VideoSource is null"
                );
            }

            setStage(
                    "video_source:success"
            );

            // ====================================================
            // MediaProjection
            // ====================================================

            capturer =
                    new ScreenCapturerAndroid(
                            projectionData,
                            new MediaProjection.Callback() {

                                @Override
                                public void onStop() {

                                    Log.i(
                                            TAG,
                                            "MediaProjection stopped"
                                    );

                                    setStage(
                                            "media_projection:stopped"
                                    );

                                    markCleanStop();

                                    stopMirror();

                                    stopSelf();
                                }
                            }
                    );

            setStage(
                    "screen_capturer:created"
            );

            // ====================================================
            // SurfaceTextureHelper
            // ====================================================

            surfaceTextureHelper =
                    SurfaceTextureHelper.create(
                            "ScreenCaptureThread",
                            eglBase.getEglBaseContext()
                    );

            if (surfaceTextureHelper == null) {

                throw new IllegalStateException(
                        "SurfaceTextureHelper is null"
                );
            }

            setStage(
                    "surface_texture_helper:success"
            );

            // ====================================================
            // Capturer initialization
            // ====================================================

            capturer.initialize(
                    surfaceTextureHelper,
                    getApplicationContext(),
                    videoSource
                            .getCapturerObserver()
            );

            setStage(
                    "capturer_initialize:success"
            );

            // ====================================================
            // Resolution
            // ====================================================

            android.util.DisplayMetrics dm =
                    getResources()
                            .getDisplayMetrics();

            int width =
                    Math.min(
                            dm.widthPixels,
                            1920
                    );

            int height =
                    Math.min(
                            dm.heightPixels,
                            1080
                    );

            Log.i(
                    TAG,
                    "Capture size: "
                            + width
                            + "x"
                            + height
            );

            setStage(
                    "capture_start:"
                            + width
                            + "x"
                            + height
            );

            // ====================================================
            // Start capture
            // ====================================================

            capturer.startCapture(
                    width,
                    height,
                    30
            );

            setStage(
                    "capture_start:success"
            );

            // ====================================================
            // VideoTrack
            // ====================================================

            screenTrack =
                    factory.createVideoTrack(
                            "screen",
                            videoSource
                    );

            if (screenTrack == null) {

                throw new IllegalStateException(
                        "screenTrack is null"
                );
            }

            screenTrack.setEnabled(
                    true
            );

            setStage(
                    "screen_track:success"
            );

            // ====================================================
            // WebSocket
            // ====================================================

            setStage(
                    "signaling_connect:start"
            );

            connectSignaling();

            setStage(
                    "signaling_connect:requested"
            );

            updateNotification(
                    "画面共有中 • ルーム "
                            + room
            );

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "startCapture failed",
                    e
            );

            saveError(
                    "画面共有開始エラー",
                    e
            );

            sendCrashReport(
                    serverUrl,
                    room,
                    "startCapture_failed",
                    e,
                    getCurrentStage()
            );

            markCleanStop();

            stopMirror();

            stopSelf();
        }
    }

    // ============================================================
    // Stage
    // ============================================================

    /*
     * 現在の処理段階だけを保存する。
     *
     * 重要：
     * ここでは session_active を変更しない。
     */
    private void setStage(
            String stage
    ) {

        Log.i(
                TAG,
                "STAGE: " + stage
        );

        getSharedPreferences(
                DEBUG_PREFS,
                MODE_PRIVATE
        )
                .edit()
                .putString(
                        KEY_STAGE,
                        stage
                )
                .apply();
    }

    private String getCurrentStage() {

        return getSharedPreferences(
                DEBUG_PREFS,
                MODE_PRIVATE
        )
                .getString(
                        KEY_STAGE,
                        "unknown"
                );
    }

    // ============================================================
    // Clean stop
    // ============================================================

    private void markCleanStop() {

        Log.i(
                TAG,
                "Marking session as clean stop"
        );

        getSharedPreferences(
                DEBUG_PREFS,
                MODE_PRIVATE
        )
                .edit()
                .putBoolean(
                        KEY_SESSION_ACTIVE,
                        false
                )
                .putBoolean(
                        KEY_CLEAN_STOP,
                        true
                )
                .apply();
    }

    // ============================================================
    // Crash report
    // ============================================================

    private void sendCrashReport(
            String reportServerUrl,
            String reportRoom,
            String stage,
            Throwable error,
            String actualStage
    ) {

        try {

            if (reportServerUrl == null
                    || reportServerUrl.isEmpty()) {

                Log.e(
                        TAG,
                        "Crash report URL is empty"
                );

                return;
            }

            String url =
                    reportServerUrl;

            if (url.endsWith("/")) {

                url =
                        url.substring(
                                0,
                                url.length() - 1
                        );
            }

            url += "/crash-report";

            JSONObject json =
                    new JSONObject();

            json.put(
                    "device",
                    Build.MANUFACTURER
                            + " "
                            + Build.MODEL
            );

            json.put(
                    "androidVersion",
                    Build.VERSION.RELEASE
            );

            json.put(
                    "androidSdk",
                    Build.VERSION.SDK_INT
            );

            json.put(
                    "appVersion",
                    "1.0"
            );

            json.put(
                    "room",
                    reportRoom == null
                            ? ""
                            : reportRoom
            );

            json.put(
                    "stage",
                    actualStage == null
                            ? stage
                            : actualStage
            );

            if (error != null) {

                json.put(
                        "exception",
                        error.getClass()
                                .getName()
                );

                json.put(
                        "message",
                        String.valueOf(
                                error.getMessage()
                        )
                );

                StringWriter sw =
                        new StringWriter();

                error.printStackTrace(
                        new PrintWriter(sw)
                );

                json.put(
                        "stackTrace",
                        sw.toString()
                );

            } else {

                /*
                 * これは「Native crash確定」
                 * という意味ではない。
                 *
                 * 前回のAndroidプロセスが
                 * clean stopを記録できないまま
                 * 終了したことを表す。
                 */
                json.put(
                        "exception",
                        "ProcessDeathOrNativeCrash"
                );

                json.put(
                        "message",
                        "Android process ended unexpectedly"
                );

                json.put(
                        "stackTrace",
                        ""
                );
            }

            String message =
                    json.toString();

            Log.i(
                    TAG,
                    "Crash report sending..."
            );

            Log.i(
                    TAG,
                    "Crash report URL: "
                            + url
            );

            Log.i(
                    TAG,
                    "Crash report JSON length="
                            + message.length()
            );

            RequestBody body =
                    RequestBody.create(
                            message,
                            MediaType.parse(
                                    "application/json; charset=utf-8"
                            )
                    );

            Request request =
                    new Request.Builder()
                            .url(url)
                            .post(body)
                            .build();

            OkHttpClient client =
                    new OkHttpClient.Builder()
                            .build();

            client.newCall(request)
                    .enqueue(
                            new okhttp3.Callback() {

                                @Override
                                public void onFailure(
                                        okhttp3.Call call,
                                        java.io.IOException e
                                ) {

                                    Log.e(
                                            TAG,
                                            "Crash report HTTP failed",
                                            e
                                    );
                                }

                                @Override
                                public void onResponse(
                                        okhttp3.Call call,
                                        Response response
                                ) {

                                    try {

                                        Log.i(
                                                TAG,
                                                "Crash report HTTP response: "
                                                        + response.code()
                                        );

                                        if (response.isSuccessful()) {

                                            Log.i(
                                                    TAG,
                                                    "Crash report sent successfully"
                                            );

                                        } else {

                                            Log.e(
                                                    TAG,
                                                    "Crash report server returned HTTP "
                                                            + response.code()
                                            );
                                        }

                                    } finally {

                                        response.close();
                                    }
                                }
                            }
                    );

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "sendCrashReport failed",
                    e
            );
        }
    }

    // ============================================================
    // Error
    // ============================================================

    private void saveError(
            String title,
            Throwable e
    ) {

        String message =
                title
                        + "\n\n"
                        + e.getClass()
                        .getName()
                        + "\n\n"
                        + String.valueOf(
                                e.getMessage()
                        );

        saveError(message);
    }

    private void saveError(
            String message
    ) {

        getSharedPreferences(
                DEBUG_PREFS,
                MODE_PRIVATE
        )
                .edit()
                .putString(
                        "last_error",
                        message
                )
                .apply();
    }

    // ============================================================
    // WebSocket
    // ============================================================

    private void connectSignaling() {

        setStage(
                "websocket:create_url"
        );

        String wsUrl =
                serverUrl
                        .replaceFirst(
                                "^https://",
                                "wss://"
                        )
                        .replaceFirst(
                                "^http://",
                                "ws://"
                        );

        Log.i(
                TAG,
                "WebSocket URL: "
                        + wsUrl
        );

        Request request =
                new Request.Builder()
                        .url(wsUrl)
                        .build();

        httpClient =
                new OkHttpClient.Builder()
                        .build();

        setStage(
                "websocket:connect"
        );

        socket =
                httpClient.newWebSocket(
                        request,
                        new WebSocketListener() {

                            @Override
                            public void onOpen(
                                    WebSocket webSocket,
                                    Response response
                            ) {

                                setStage(
                                        "websocket:onOpen"
                                );

                                try {

                                    JSONObject join =
                                            new JSONObject();

                                    join.put(
                                            "type",
                                            "join"
                                    );

                                    join.put(
                                            "room",
                                            room
                                    );

                                    join.put(
                                            "role",
                                            "broadcaster"
                                    );

                                    setStage(
                                            "websocket:send_join"
                                    );

                                    webSocket.send(
                                            join.toString()
                                    );

                                    setStage(
                                            "websocket:join_sent"
                                    );

                                } catch (Throwable e) {

                                    Log.e(
                                            TAG,
                                            "join error",
                                            e
                                    );

                                    sendCrashReport(
                                            serverUrl,
                                            room,
                                            "websocket_join_error",
                                            e,
                                            getCurrentStage()
                                    );
                                }
                            }

                            @Override
                            public void onMessage(
                                    WebSocket webSocket,
                                    String text
                            ) {

                                Log.i(
                                        TAG,
                                        "WebSocket message: "
                                                + text
                                );

                                handleSignal(text);
                            }

                            @Override
                            public void onFailure(
                                    WebSocket webSocket,
                                    Throwable t,
                                    Response response
                            ) {

                                Log.e(
                                        TAG,
                                        "WebSocket failure",
                                        t
                                );

                                setStage(
                                        "websocket:failure"
                                );

                                updateNotification(
                                        "WebSocket接続エラー"
                                );
                            }

                            @Override
                            public void onClosed(
                                    WebSocket webSocket,
                                    int code,
                                    String reason
                            ) {

                                Log.i(
                                        TAG,
                                        "WebSocket closed: "
                                                + reason
                                );

                                /*
                                 * WebSocketが閉じただけでは
                                 * Androidプロセスの異常終了ではない。
                                 */
                                setStage(
                                        "websocket:closed"
                                );
                            }
                        }
                );
    }

    // ============================================================
    // Signaling
    // ============================================================

    private void handleSignal(
            String text
    ) {

        try {

            JSONObject msg =
                    new JSONObject(text);

            String type =
                    msg.optString(
                            "type"
                    );

            Log.i(
                    TAG,
                    "Signal type: "
                            + type
            );

            // ----------------------------------------------------
            // hello
            // ----------------------------------------------------

            if ("hello".equals(type)) {

                clientId =
                        msg.optString(
                                "clientId"
                        );

                setStage(
                        "signal:hello"
                );

                return;
            }

            // ----------------------------------------------------
            // joined
            // ----------------------------------------------------

            if ("joined".equals(type)) {

                setStage(
                        "signal:joined"
                );

                updateNotification(
                        "待機中 • ルーム "
                                + room
                );

                return;
            }

            // ----------------------------------------------------
            // peer-joined
            // ----------------------------------------------------

            if ("peer-joined".equals(type)) {

                setStage(
                        "signal:peer_joined"
                );

                String peerId =
                        msg.optString(
                                "peerId"
                        );

                String peerRole =
                        msg.optString(
                                "role"
                        );

                Log.i(
                        TAG,
                        "Peer joined: "
                                + peerId
                                + " role="
                                + peerRole
                );

                if ("viewer".equals(
                        peerRole
                )
                        && !peerId.isEmpty()) {

                    setStage(
                            "peer:create_requested"
                    );

                    PeerConnection pc =
                            createPeer(
                                    peerId
                            );

                    if (pc != null) {

                        setStage(
                                "peer:create_success"
                        );

                        createOffer(
                                peerId,
                                pc
                        );

                    } else {

                        Log.e(
                                TAG,
                                "createPeer returned null"
                        );

                        setStage(
                                "peer:create_null"
                        );
                    }
                }

                return;
            }

            // ----------------------------------------------------
            // answer
            // ----------------------------------------------------

            if ("answer".equals(type)) {

                setStage(
                        "signal:answer"
                );

                String from =
                        msg.optString(
                                "from"
                        );

                PeerConnection pc =
                        peers.get(from);

                if (pc != null) {

                    JSONObject sdp =
                            msg.optJSONObject(
                                    "data"
                            );

                    if (sdp != null) {

                        SessionDescription answer =
                                new SessionDescription(
                                        SessionDescription
                                                .Type
                                                .ANSWER,
                                        sdp.optString(
                                                "sdp"
                                        )
                                );

                        setStage(
                                "answer:setRemoteDescription"
                        );

                        pc.setRemoteDescription(
                                new SimpleSdpObserver(),
                                answer
                        );

                        setStage(
                                "answer:setRemoteDescription:called"
                        );
                    }
                }

                return;
            }

            // ----------------------------------------------------
            // candidate
            // ----------------------------------------------------

            if ("candidate".equals(type)) {

                setStage(
                        "signal:candidate"
                );

                String from =
                        msg.optString(
                                "from"
                        );

                PeerConnection pc =
                        peers.get(from);

                JSONObject c =
                        msg.optJSONObject(
                                "data"
                        );

                if (pc != null
                        && c != null) {

                    IceCandidate candidate =
                            new IceCandidate(
                                    c.optString(
                                            "sdpMid"
                                    ),
                                    c.optInt(
                                            "sdpMLineIndex"
                                    ),
                                    c.optString(
                                            "candidate"
                                    )
                            );

                    setStage(
                            "candidate:addIceCandidate"
                    );

                    pc.addIceCandidate(
                            candidate
                    );

                    setStage(
                            "candidate:addIceCandidate:called"
                    );
                }
            }

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "Signal parse error",
                    e
            );

            sendCrashReport(
                    serverUrl,
                    room,
                    "signal_parse_error",
                    e,
                    getCurrentStage()
            );
        }
    }

    // ============================================================
    // PeerConnection
    // ============================================================

    private PeerConnection createPeer(
            String peerId
    ) {

        setStage(
                "peer:create:start"
        );

        PeerConnection existing =
                peers.get(peerId);

        if (existing != null) {

            setStage(
                    "peer:create:existing"
            );

            return existing;
        }

        if (factory == null
                || screenTrack == null) {

            Log.e(
                    TAG,
                    "WebRTC resources are not ready"
            );

            setStage(
                    "peer:create:resources_not_ready"
            );

            return null;
        }

        List<PeerConnection.IceServer>
                iceServers =
                new ArrayList<>();

        iceServers.add(
                PeerConnection.IceServer
                        .builder(
                                "stun:stun.l.google.com:19302"
                        )
                        .createIceServer()
        );

        setStage(
                "peer:ice_servers_created"
        );

        PeerConnection.RTCConfiguration config =
                new PeerConnection.RTCConfiguration(
                        iceServers
                );

        setStage(
                "peer:createPeerConnection:start"
        );

        PeerConnection pc =
                factory.createPeerConnection(
                        config,
                        new PeerConnection.Observer() {

                            @Override
                            public void onSignalingChange(
                                    PeerConnection.SignalingState state
                            ) {

                                Log.i(
                                        TAG,
                                        "Signaling state "
                                                + peerId
                                                + ": "
                                                + state
                                );
                            }

                            @Override
                            public void onIceConnectionChange(
                                    PeerConnection.IceConnectionState state
                            ) {

                                Log.i(
                                        TAG,
                                        "ICE "
                                                + peerId
                                                + ": "
                                                + state
                                );
                            }

                            @Override
                            public void onIceConnectionReceivingChange(
                                    boolean receiving
                            ) {}

                            @Override
                            public void onIceGatheringChange(
                                    PeerConnection.IceGatheringState state
                            ) {

                                Log.i(
                                        TAG,
                                        "ICE gathering "
                                                + peerId
                                                + ": "
                                                + state
                                );
                            }

                            @Override
                            public void onIceCandidate(
                                    IceCandidate candidate
                            ) {

                                sendCandidate(
                                        peerId,
                                        candidate
                                );
                            }

                            @Override
                            public void onIceCandidatesRemoved(
                                    IceCandidate[] candidates
                            ) {}

                            @Override
                            public void onAddStream(
                                    org.webrtc.MediaStream stream
                            ) {}

                            @Override
                            public void onRemoveStream(
                                    org.webrtc.MediaStream stream
                            ) {}

                            @Override
                            public void onDataChannel(
                                    DataChannel dataChannel
                            ) {}

                            @Override
                            public void onRenegotiationNeeded() {

                                Log.i(
                                        TAG,
                                        "Renegotiation needed: "
                                                + peerId
                                );
                            }

                            @Override
                            public void onAddTrack(
                                    RtpReceiver receiver,
                                    org.webrtc.MediaStream[] streams
                            ) {}
                        }
                );

        setStage(
                "peer:createPeerConnection:returned"
        );

        if (pc != null) {

            /*
             * 重要な調査ポイント。
             *
             * Viewer接続時に
             * addTrack()付近で落ちるか確認する。
             */
            setStage(
                    "peer:addTrack:start"
            );

            pc.addTrack(
                    screenTrack
            );

            setStage(
                    "peer:addTrack:success"
            );

            peers.put(
                    peerId,
                    pc
            );

            setStage(
                    "peer:stored"
            );
        }

        return pc;
    }

    // ============================================================
    // Offer
    // ============================================================

    private void createOffer(
            String peerId,
            PeerConnection pc
    ) {

        setStage(
                "offer:create:start"
        );

        MediaConstraints constraints =
                new MediaConstraints();

        constraints.mandatory.add(
                new MediaConstraints.KeyValuePair(
                        "OfferToReceiveAudio",
                        "false"
                )
        );

        constraints.mandatory.add(
                new MediaConstraints.KeyValuePair(
                        "OfferToReceiveVideo",
                        "false"
                )
        );

        setStage(
                "offer:createOffer:call"
        );

        pc.createOffer(
                new SdpObserver() {

                    @Override
                    public void onCreateSuccess(
                            SessionDescription sdp
                    ) {

                        setStage(
                                "offer:create:success"
                        );

                        pc.setLocalDescription(
                                new SimpleSdpObserver() {

                                    @Override
                                    public void onSetSuccess() {

                                        setStage(
                                                "offer:setLocalDescription:success"
                                        );

                                        sendSdp(
                                                "offer",
                                                peerId,
                                                sdp
                                        );
                                    }

                                    @Override
                                    public void onSetFailure(
                                            String error
                                    ) {

                                        Log.e(
                                                TAG,
                                                "setLocalDescription failed: "
                                                        + error
                                        );

                                        setStage(
                                                "offer:setLocalDescription:failure"
                                        );
                                    }
                                },
                                sdp
                        );

                        setStage(
                                "offer:setLocalDescription:called"
                        );
                    }

                    @Override
                    public void onSetSuccess() {}

                    @Override
                    public void onCreateFailure(
                            String error
                    ) {

                        Log.e(
                                TAG,
                                "Offer create failed: "
                                        + error
                        );

                        setStage(
                                "offer:create:failure"
                        );
                    }

                    @Override
                    public void onSetFailure(
                            String error
                    ) {

                        Log.e(
                                TAG,
                                "Offer set failure: "
                                        + error
                        );

                        setStage(
                                "offer:set:failure"
                        );
                    }
                },
                constraints
        );
    }

    // ============================================================
    // SDP
    // ============================================================

    private void sendSdp(
            String type,
            String to,
            SessionDescription sdp
    ) {

        try {

            JSONObject data =
                    new JSONObject();

            data.put(
                    "type",
                    sdp.type.canonicalForm()
            );

            data.put(
                    "sdp",
                    sdp.description
            );

            JSONObject msg =
                    new JSONObject();

            msg.put(
                    "type",
                    type
            );

            msg.put(
                    "to",
                    to
            );

            msg.put(
                    "data",
                    data
            );

            if (socket != null) {

                /*
                 * SDP送信直前
                 */
                setStage(
                        "sdp:send:start"
                );

                String message =
                        msg.toString();

                Log.i(
                        TAG,
                        "Sending SDP length="
                                + message.length()
                );

                /*
                 * WebSocket送信
                 */
                boolean sent =
                        socket.send(
                                message
                        );

                /*
                 * socket.send()から戻ったか確認
                 */
                Log.i(
                        TAG,
                        "socket.send returned: "
                                + sent
                );

                setStage(
                        "sdp:send:returned:"
                                + sent
                );
            }

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "sendSdp error",
                    e
            );

            sendCrashReport(
                    serverUrl,
                    room,
                    "send_sdp_error",
                    e,
                    getCurrentStage()
            );
        }
    }

    // ============================================================
    // Candidate
    // ============================================================

    private void sendCandidate(
            String to,
            IceCandidate candidate
    ) {

        try {

            JSONObject data =
                    new JSONObject();

            data.put(
                    "candidate",
                    candidate.sdp
            );

            data.put(
                    "sdpMid",
                    candidate.sdpMid
            );

            data.put(
                    "sdpMLineIndex",
                    candidate.sdpMLineIndex
            );

            JSONObject msg =
                    new JSONObject();

            msg.put(
                    "type",
                    "candidate"
            );

            msg.put(
                    "to",
                    to
            );

            msg.put(
                    "data",
                    data
            );

            if (socket != null) {

                setStage(
                        "candidate:send"
                );

                boolean sent =
                        socket.send(
                                msg.toString()
                        );

                Log.i(
                        TAG,
                        "Candidate socket.send returned: "
                                + sent
                );
            }

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "sendCandidate error",
                    e
            );

            sendCrashReport(
                    serverUrl,
                    room,
                    "send_candidate_error",
                    e,
                    getCurrentStage()
            );
        }
    }

    // ============================================================
    // Stop / cleanup
    // ============================================================

    private synchronized void stopMirror() {

        if (stopping) {

            Log.i(
                    TAG,
                    "stopMirror already running"
            );

            return;
        }

        stopping = true;

        setStage(
                "stop:start"
        );

        try {

            // ----------------------------------------------------
            // PeerConnections
            // ----------------------------------------------------

            for (
                    PeerConnection pc :
                    peers.values()
            ) {

                try {

                    pc.close();

                } catch (Throwable ignored) {}
            }

            peers.clear();

            // ----------------------------------------------------
            // WebSocket
            // ----------------------------------------------------

            if (socket != null) {

                try {

                    socket.close(
                            1000,
                            "stopped"
                    );

                } catch (Throwable ignored) {}

                socket = null;
            }

            // ----------------------------------------------------
            // Capturer
            // ----------------------------------------------------

            if (capturer != null) {

                try {

                    capturer.stopCapture();

                } catch (Throwable ignored) {}

                try {

                    capturer.dispose();

                } catch (Throwable ignored) {}

                capturer = null;
            }

            // ----------------------------------------------------
            // VideoSource
            // ----------------------------------------------------

            if (videoSource != null) {

                try {

                    videoSource.dispose();

                } catch (Throwable ignored) {}

                videoSource = null;
            }

            // ----------------------------------------------------
            // VideoTrack
            // ----------------------------------------------------

            if (screenTrack != null) {

                try {

                    screenTrack.dispose();

                } catch (Throwable ignored) {}

                screenTrack = null;
            }

            // ----------------------------------------------------
            // SurfaceTextureHelper
            // ----------------------------------------------------

            if (surfaceTextureHelper != null) {

                try {

                    surfaceTextureHelper.dispose();

                } catch (Throwable ignored) {}

                surfaceTextureHelper = null;
            }

            // ----------------------------------------------------
            // PeerConnectionFactory
            // ----------------------------------------------------

            if (factory != null) {

                try {

                    factory.dispose();

                } catch (Throwable ignored) {}

                factory = null;
            }

            // ----------------------------------------------------
            // EGL
            // ----------------------------------------------------

            if (eglBase != null) {

                try {

                    eglBase.release();

                } catch (Throwable ignored) {}

                eglBase = null;
            }

            // ----------------------------------------------------
            // HTTP client
            // ----------------------------------------------------

            if (httpClient != null) {

                try {

                    httpClient
                            .dispatcher()
                            .executorService()
                            .shutdown();

                } catch (Throwable ignored) {}

                httpClient = null;
            }

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "stopMirror error",
                    e
            );

            sendCrashReport(
                    serverUrl,
                    room,
                    "stopMirror_error",
                    e,
                    getCurrentStage()
            );

        } finally {

            /*
             * stopMirror()まで到達した場合は、
             * セッションを終了状態にする。
             */
            markCleanStop();

            getSharedPreferences(
                    DEBUG_PREFS,
                    MODE_PRIVATE
            )
                    .edit()
                    .putString(
                            KEY_STAGE,
                            "normal_stop"
                    )
                    .apply();

            Log.i(
                    TAG,
                    "Mirror stopped normally"
            );

            stopping = false;
        }
    }

    // ============================================================
    // Notification
    // ============================================================

    private Notification buildNotification(
            String text
    ) {

        Intent intent =
                new Intent(
                        this,
                        MainActivity.class
                );

        PendingIntent pi =
                PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        return new NotificationCompat.Builder(
                this,
                CHANNEL_ID
        )
                .setSmallIcon(
                        android.R.drawable.ic_menu_share
                )
                .setContentTitle(
                        "Screen Mirror"
                )
                .setContentText(
                        text
                )
                .setContentIntent(
                        pi
                )
                .setOngoing(true)
                .setCategory(
                        NotificationCompat
                                .CATEGORY_SERVICE
                )
                .build();
    }

    private void updateNotification(
            String text
    ) {

        NotificationManager manager =
                getSystemService(
                        NotificationManager.class
                );

        if (manager != null) {

            manager.notify(
                    NOTIFICATION_ID,
                    buildNotification(text)
            );
        }
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Screen Mirror",
                            NotificationManager
                                    .IMPORTANCE_LOW
                    );

            channel.setDescription(
                    "画面共有中の通知"
            );

            NotificationManager manager =
                    getSystemService(
                            NotificationManager.class
                    );

            if (manager != null) {

                manager.createNotificationChannel(
                        channel
                );
            }
        }
    }

    // ============================================================
    // Destroy
    // ============================================================

    @Override
    public void onDestroy() {

        Log.i(
                TAG,
                "Service onDestroy"
        );

        /*
         * onDestroy()まで来た場合は、
         * AndroidからServiceが正常に破棄されたと判断する。
         */
        markCleanStop();

        stopMirror();

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(
            Intent intent
    ) {
        return null;
    }

    // ============================================================
    // SDP Observer
    // ============================================================

    private static class SimpleSdpObserver
            implements SdpObserver {

        @Override
        public void onCreateSuccess(
                SessionDescription sdp
        ) {}

        @Override
        public void onSetSuccess() {}

        @Override
        public void onCreateFailure(
                String error
        ) {}

        @Override
        public void onSetFailure(
                String error
        ) {}
    }
}
:::
