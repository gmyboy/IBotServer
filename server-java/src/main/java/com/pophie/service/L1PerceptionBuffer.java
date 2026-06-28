package com.pophie.service;

import com.pophie.util.TimeUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1 感知层：瞬时多模态感知缓存（仅最近若干帧，进程内，不入库）。
 * 对应 memory.py L1PerceptionBuffer（capacity=8）。
 */
@Component
public class L1PerceptionBuffer {

    private final int capacity = 8;
    private final Map<String, Deque<Map<String, Object>>> buf = new ConcurrentHashMap<>();

    public synchronized Map<String, Object> push(String robotId, Map<String, Object> frameIn) {
        Map<String, Object> frame = new LinkedHashMap<>(frameIn);
        frame.putIfAbsent("ts", TimeUtil.isoNow());
        frame.putIfAbsent("layer", "L1");
        Deque<Map<String, Object>> dq = buf.computeIfAbsent(robotId, k -> new ArrayDeque<>());
        if (dq.size() >= capacity) dq.pollFirst();
        dq.addLast(frame);
        return frame;
    }

    public synchronized List<Map<String, Object>> snapshot(String robotId) {
        Deque<Map<String, Object>> dq = buf.get(robotId);
        return dq == null ? new ArrayList<>() : new ArrayList<>(dq);
    }

    public synchronized void clear(String robotId) {
        buf.remove(robotId);
    }
}
