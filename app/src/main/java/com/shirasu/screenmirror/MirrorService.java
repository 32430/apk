package com.shirasu.screenmirror;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.Camera2Enumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.MediaStreamTrack;
import org.webrtc.ScreenCapturerAndroid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MirrorService extends Service {
    public static final String ACTION_START = "com.shirasu.screenmirror.START";
    public static final String ACTION_STOP = "com.shirasu.screenmirror.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_PROJECTION_DATA = "projectionData";
    public static final String EXTRA_SERVER_URL = "serverUrl";
    public static final String EXTRA_ROOM = "room";

    private static final String TAG = "ScreenMirror";
    private static final String CHANNEL_ID = "screen_mirror";
    private static final int NOTIFICATION_ID = 77;

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

    private final Map<String, PeerConnection> peers = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            stopMirror();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(intent.getAction())) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
            Intent projectionData = intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
            serverUrl = intent.getStringExtra(EXTRA_SERVER_URL);
            room = intent.getStringExtra(EXTRA_ROOM);

            if (projectionData == null || serverUrl == null || room == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                        NOTIFICATION_ID,
                        buildNotification("画面共有を準備中..."),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                );
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("画面共有を準備中..."));
            }

            startCapture(resultCode, projectionData);
        }

        return START_STICKY;
    }

    private void startCapture(int resultCode, Intent projectionData) {
        try {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                            .builder(getApplicationContext())
                            .setEnableInternalTracer(false)
                            .createInitializationOptions()
            );

            eglBase = EglBase.create();

            PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
            DefaultVideoEncoderFactory encoderFactory =
                    new DefaultVideoEncoderFactory(
                            eglBase.getEglBaseContext(),
                            true,
                            false
                    );
            DefaultVideoDecoderFactory decoderFactory =
                    new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

            factory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory();

            videoSource = factory.createVideoSource(false);

            capturer = new ScreenCapturerAndroid(
                    projectionData,
                    new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            Log.i(TAG, "MediaProjection stopped");
                            stopMirror();
                            stopSelf();
                        }
                    }
            );

            surfaceTextureHelper =
                    SurfaceTextureHelper.create(
                            "ScreenCaptureThread",
                            eglBase.getEglBaseContext()
                    );

            capturer.initialize(
                    surfaceTextureHelper,
                    getApplicationContext(),
                    videoSource.getCapturerObserver()
            );

            // 端末の解像度に合わせる。負荷を抑えるため上限を1920x1080にする。
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int width = Math.min(dm.widthPixels, 1920);
            int height = Math.min(dm.heightPixels, 1080);

            capturer.startCapture(width, height, 30);

            screenTrack = factory.createVideoTrack("screen", videoSource);
            screenTrack.setEnabled(true);

            connectSignaling();

            updateNotification("画面共有中 • ルーム " + room);

        } catch (Exception e) {
            Log.e(TAG, "startCapture failed", e);
            stopMirror();
            stopSelf();
        }
    }

    private void connectSignaling() {
        String wsUrl = serverUrl
                .replaceFirst("^https://", "wss://")
                .replaceFirst("^http://", "ws://");

        Request request = new Request.Builder()
                .url(wsUrl)
                .build();

        httpClient = new OkHttpClient.Builder().build();

        socket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                try {
                    JSONObject join = new JSONObject();
                    join.put("type", "join");
                    join.put("room", room);
                    join.put("role", "broadcaster");
                    webSocket.send(join.toString());
                } catch (Exception e) {
                    Log.e(TAG, "join error", e);
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleSignal(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "WebSocket failure", t);
                updateNotification("WebSocket接続エラー");
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.i(TAG, "WebSocket closed: " + reason);
            }
        });
    }

    private void handleSignal(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("type");

            if ("hello".equals(type)) {
                clientId = msg.optString("clientId");
                return;
            }

            if ("joined".equals(type)) {
                updateNotification("待機中 • ルーム " + room);
                return;
            }

            if ("peer-joined".equals(type)) {
                String peerId = msg.optString("peerId");
                String peerRole = msg.optString("role");

                if ("viewer".equals(peerRole) && !peerId.isEmpty()) {
                    PeerConnection pc = createPeer(peerId);
                    createOffer(peerId, pc);
                }
                return;
            }

            if ("answer".equals(type)) {
                String from = msg.optString("from");
                PeerConnection pc = peers.get(from);
                if (pc != null) {
                    JSONObject sdp = msg.optJSONObject("data");
                    if (sdp != null) {
                        SessionDescription answer = new SessionDescription(
                                SessionDescription.Type.ANSWER,
                                sdp.optString("sdp")
                        );
                        pc.setRemoteDescription(new SimpleSdpObserver(), answer);
                    }
                }
                return;
            }

            if ("candidate".equals(type)) {
                String from = msg.optString("from");
                PeerConnection pc = peers.get(from);
                JSONObject c = msg.optJSONObject("data");
                if (pc != null && c != null) {
                    IceCandidate candidate = new IceCandidate(
                            c.optString("sdpMid"),
                            c.optInt("sdpMLineIndex"),
                            c.optString("candidate")
                    );
                    pc.addIceCandidate(candidate);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Signal parse error", e);
        }
    }

    private PeerConnection createPeer(String peerId) {
        PeerConnection existing = peers.get(peerId);
        if (existing != null) return existing;

        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration config =
                new PeerConnection.RTCConfiguration(iceServers);

        PeerConnection pc = factory.createPeerConnection(
                config,
                new PeerConnection.Observer() {
                    @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
                    @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                        Log.i(TAG, "ICE " + peerId + ": " + state);
                    }
                    @Override public void onIceConnectionReceivingChange(boolean receiving) {}
                    @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
                    @Override public void onIceCandidate(IceCandidate candidate) {
                        sendCandidate(peerId, candidate);
                    }
                    @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
                    @Override public void onAddStream(org.webrtc.MediaStream stream) {}
                    @Override public void onRemoveStream(org.webrtc.MediaStream stream) {}
                    @Override public void onDataChannel(DataChannel dataChannel) {}
                    @Override public void onRenegotiationNeeded() {}
                    @Override public void onAddTrack(RtpReceiver receiver, org.webrtc.MediaStream[] streams) {}
                }
        );

        if (pc != null) {
            pc.addTrack(screenTrack);
            peers.put(peerId, pc);
        }

        return pc;
    }

    private void createOffer(String peerId, PeerConnection pc) {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        pc.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                pc.setLocalDescription(new SimpleSdpObserver() {
                    @Override public void onSetSuccess() {
                        sendSdp("offer", peerId, sdp);
                    }
                }, sdp);
            }
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String error) {
                Log.e(TAG, "Offer create failed: " + error);
            }
            @Override public void onSetFailure(String error) {}
        }, constraints);
    }

    private void sendSdp(String type, String to, SessionDescription sdp) {
        try {
            JSONObject data = new JSONObject();
            data.put("type", sdp.type.canonicalForm());
            data.put("sdp", sdp.description);

            JSONObject msg = new JSONObject();
            msg.put("type", type);
            msg.put("to", to);
            msg.put("data", data);

            if (socket != null) socket.send(msg.toString());
        } catch (Exception e) {
            Log.e(TAG, "sendSdp error", e);
        }
    }

    private void sendCandidate(String to, IceCandidate candidate) {
        try {
            JSONObject data = new JSONObject();
            data.put("candidate", candidate.sdp);
            data.put("sdpMid", candidate.sdpMid);
            data.put("sdpMLineIndex", candidate.sdpMLineIndex);

            JSONObject msg = new JSONObject();
            msg.put("type", "candidate");
            msg.put("to", to);
            msg.put("data", data);

            if (socket != null) socket.send(msg.toString());
        } catch (Exception e) {
            Log.e(TAG, "sendCandidate error", e);
        }
    }

    private void stopMirror() {
        try {
            for (PeerConnection pc : peers.values()) {
                try { pc.close(); } catch (Exception ignored) {}
            }
            peers.clear();

            if (socket != null) {
                try { socket.close(1000, "stopped"); } catch (Exception ignored) {}
                socket = null;
            }

            if (capturer != null) {
                try { capturer.stopCapture(); } catch (Exception ignored) {}
                try { capturer.dispose(); } catch (Exception ignored) {}
                capturer = null;
            }

            if (videoSource != null) {
                try { videoSource.dispose(); } catch (Exception ignored) {}
                videoSource = null;
            }

            if (screenTrack != null) {
                try { screenTrack.dispose(); } catch (Exception ignored) {}
                screenTrack = null;
            }

            if (surfaceTextureHelper != null) {
                try { surfaceTextureHelper.dispose(); } catch (Exception ignored) {}
                surfaceTextureHelper = null;
            }

            if (factory != null) {
                try { factory.dispose(); } catch (Exception ignored) {}
                factory = null;
            }

            if (eglBase != null) {
                try { eglBase.release(); } catch (Exception ignored) {}
                eglBase = null;
            }

            if (httpClient != null) {
                try { httpClient.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
                httpClient = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "stopMirror error", e);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("Screen Mirror")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Mirror",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("画面共有中の通知");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        stopMirror();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }
}
