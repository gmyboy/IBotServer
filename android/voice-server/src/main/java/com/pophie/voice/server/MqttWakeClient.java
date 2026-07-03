package com.pophie.voice.server;

import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MQTT唤醒客户端：连接Broker，订阅wake topic，收到唤醒通知后回调。
 * 自动重连，断线后指数退避重试。
 */
public final class MqttWakeClient {

    private static final String TAG = "MqttWake";
    private static final String TOPIC_PREFIX = "pophie/robot/";
    private static final int QOS = 1;

    public interface WakeListener {
        void onMqttConnected();
        void onMqttDisconnected(String reason);
        void onMqttError(String message);
        /** 收到服务端唤醒通知，userId为null表示推送给所有用户 */
        void onWake(String robotId, String userId, int msgCount);
    }

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mqtt-reconnect");
                t.setDaemon(true);
                return t;
            });

    private MqttClient client;
    private volatile WakeListener listener;
    private volatile String brokerUrl;
    private volatile String robotId;
    private volatile String userId;
    private volatile String username;
    private volatile String password;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> reconnectFuture;
    private volatile int reconnectDelaySec = 2;
    private final Object connectLock = new Object();

    public void setListener(WakeListener listener) {
        this.listener = listener;
    }

    public void configure(String brokerUrl, String robotId, String userId,
                          String username, String password) {
        this.brokerUrl = brokerUrl;
        this.robotId = robotId;
        this.userId = userId;
        this.username = username;
        this.password = password;
    }

    public void updateRobotUser(String robotId, String userId) {
        String oldTopic = getWakeTopic(this.robotId);
        this.robotId = robotId;
        this.userId = userId;
        if (connected.get() && client != null) {
            try {
                client.unsubscribe(oldTopic);
            } catch (Exception ignored) {}
            subscribeWake();
        }
    }

    public boolean isEnabled() {
        String b = brokerUrl;
        return b != null && !b.trim().isEmpty();
    }

    public boolean isConnected() {
        return connected.get();
    }

    public void start() {
        if (!isEnabled()) {
            Log.i(TAG, "MQTT broker not configured, skipping");
            return;
        }
        if (!started.compareAndSet(false, true)) return;
        scheduler.execute(this::connect);
    }

    public void stop() {
        started.set(false);
        ScheduledFuture<?> f = reconnectFuture;
        if (f != null) f.cancel(false);
        disconnect();
    }

    private void connect() {
        if (!started.get() || !isEnabled()) return;
        String b = brokerUrl;
        String rid = robotId;
        if (b == null || b.isEmpty() || rid == null || rid.isEmpty()) {
            scheduleReconnect(5);
            return;
        }
        synchronized (connectLock) {
            disconnect();
            try {
                String clientId = "pophie-android-" + android.os.Process.myPid()
                        + "-" + System.currentTimeMillis() % 100000;
                client = new MqttClient(b, clientId, new MemoryPersistence());
                client.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String serverURI) {
                        connected.set(true);
                        reconnectDelaySec = 2;
                        Log.i(TAG, "connected to " + serverURI + (reconnect ? " (reconnected)" : ""));
                        subscribeWake();
                        WakeListener l = listener;
                        if (l != null) l.onMqttConnected();
                    }

                    @Override
                    public void connectionLost(Throwable cause) {
                        connected.set(false);
                        String msg = cause != null ? cause.getMessage() : "unknown";
                        Log.w(TAG, "connection lost: " + msg);
                        WakeListener l = listener;
                        if (l != null) l.onMqttDisconnected(msg);
                        if (started.get()) scheduleReconnect(reconnectDelaySec);
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        handleMessage(message.getPayload());
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {}
                });

                MqttConnectOptions opts = new MqttConnectOptions();
                opts.setCleanSession(true);
                opts.setAutomaticReconnect(false);
                opts.setKeepAliveInterval(30);
                opts.setConnectionTimeout(10);
                if (username != null && !username.isEmpty()) {
                    opts.setUserName(username);
                }
                if (password != null && !password.isEmpty()) {
                    opts.setPassword(password.toCharArray());
                }
                Log.i(TAG, "connecting to " + b + " robot=" + rid);
                client.connect(opts);
            } catch (MqttException e) {
                Log.w(TAG, "connect failed: " + e.getMessage());
                connected.set(false);
                WakeListener l = listener;
                if (l != null) l.onMqttError(e.getMessage());
                if (started.get()) scheduleReconnect(reconnectDelaySec);
            }
        }
    }

    private void subscribeWake() {
        String rid = robotId;
        if (client == null || rid == null || rid.isEmpty()) return;
        String topic = getWakeTopic(rid);
        try {
            client.subscribe(topic, QOS);
            Log.i(TAG, "subscribed: " + topic);
        } catch (MqttException e) {
            Log.w(TAG, "subscribe failed: " + e.getMessage());
        }
    }

    private String getWakeTopic(String rid) {
        return TOPIC_PREFIX + rid + "/wake";
    }

    private void handleMessage(byte[] payload) {
        try {
            JSONObject obj = new JSONObject(new String(payload, "UTF-8"));
            String type = obj.optString("type", "");
            if (!"wake".equals(type)) return;
            String rid = obj.optString("robot_id", "");
            String uid = obj.optString("user_id", "");
            int count = obj.optInt("msg_count", 1);
            String currentRid = robotId;
            String currentUid = userId;
            if (!rid.isEmpty() && !rid.equals(currentRid)) return;
            if (uid != null && !uid.isEmpty() && currentUid != null
                    && !currentUid.isEmpty() && !uid.equals(currentUid)) return;
            Log.i(TAG, "wake received rid=" + rid + " uid=" + uid + " count=" + count);
            WakeListener l = listener;
            if (l != null) l.onWake(rid, uid.isEmpty() ? null : uid, count);
        } catch (Exception e) {
            Log.w(TAG, "handle wake msg failed: " + e.getMessage());
        }
    }

    private void disconnect() {
        synchronized (connectLock) {
            MqttClient c = client;
            client = null;
            connected.set(false);
            if (c != null) {
                try {
                    if (c.isConnected()) c.disconnect(1000);
                    c.close();
                } catch (Exception ignored) {}
            }
        }
    }

    private void scheduleReconnect(int delaySec) {
        ScheduledFuture<?> old = reconnectFuture;
        if (old != null) old.cancel(false);
        int d = Math.min(delaySec, 60);
        reconnectFuture = scheduler.schedule(() -> {
            reconnectDelaySec = Math.min(d * 2, 60);
            connect();
        }, d, TimeUnit.SECONDS);
    }
}
