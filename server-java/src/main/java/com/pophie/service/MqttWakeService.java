package com.pophie.service;

import com.pophie.util.JsonUtil;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MQTT唤醒通知服务。
 *
 * 工作模式：
 * 1. 服务端不保持WebSocket长连接，有主动消息时通过MQTT Broker发布轻量唤醒通知
 * 2. 机器人/客户端订阅 robot/{robotId}/wake topic，收到通知后通过WebSocket短连接拉取消息
 * 3. 如果MQTT未配置（broker为空），服务退化为兼容模式：直接推送到已连接的WebSocket
 */
@Service
public class MqttWakeService {

    private static final Logger log = LoggerFactory.getLogger(MqttWakeService.class);
    private static final String TOPIC_PREFIX = "pophie/robot/";
    private static final int QOS = 1;

    @Value("${mqtt.broker:}")
    private String brokerUrl;

    @Value("${mqtt.username:}")
    private String username;

    @Value("${mqtt.password:}")
    private String password;

    @Value("${mqtt.client-id:pophie-server}")
    private String clientId;

    private MqttClient client;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        if (brokerUrl == null || brokerUrl.isBlank()) {
            log.info("[mqtt] broker not configured, running in WebSocket-only compatibility mode");
            return;
        }
        try {
            client = new MqttClient(brokerUrl, clientId + "-" + System.currentTimeMillis(),
                    new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);
            opts.setAutomaticReconnect(true);
            opts.setKeepAliveInterval(30);
            opts.setConnectionTimeout(10);
            if (username != null && !username.isBlank()) {
                opts.setUserName(username);
            }
            if (password != null && !password.isBlank()) {
                opts.setPassword(password.toCharArray());
            }
            client.connect(opts);
            connected.set(true);
            log.info("[mqtt] connected to broker={}", brokerUrl);
        } catch (MqttException e) {
            log.warn("[mqtt] connect failed, running in WebSocket-only mode: {}", e.getMessage());
            client = null;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (client != null && connected.get()) {
            try {
                client.disconnect(2000);
                client.close();
            } catch (Exception e) {
                log.warn("[mqtt] disconnect error: {}", e.getMessage());
            }
        }
    }

    public boolean isEnabled() {
        return connected.get() && client != null && client.isConnected();
    }

    /**
     * 发布唤醒通知到指定机器人的MQTT topic。
     *
     * @param robotId 机器人ID
     * @param userId  目标用户ID（可为null表示推送给机器人所有用户）
     * @param msgCount 待拉取消息数量
     */
    public void publishWake(String robotId, String userId, int msgCount) {
        if (!isEnabled()) return;
        try {
            String topic = TOPIC_PREFIX + robotId + "/wake";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "wake");
            payload.put("robot_id", robotId);
            payload.put("user_id", userId);
            payload.put("msg_count", msgCount);
            payload.put("ts", System.currentTimeMillis());
            byte[] bytes = JsonUtil.dumps(payload).getBytes("UTF-8");
            MqttMessage msg = new MqttMessage(bytes);
            msg.setQos(QOS);
            msg.setRetained(false);
            client.publish(topic, msg);
            log.debug("[mqtt] wake published robot={} user={} count={}", robotId, userId, msgCount);
        } catch (Exception e) {
            log.warn("[mqtt] publish wake failed: {}", e.getMessage());
        }
    }
}
