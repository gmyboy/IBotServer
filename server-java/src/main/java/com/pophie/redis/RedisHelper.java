package com.pophie.redis;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public interface RedisHelper {

    void hashPut(String key, String hashKey, Object value);
    Map<String, Object> hashFindAll(String key);
    Object hashGet(String key, String hashKey);
    void hashRemove(String key, String hashKey);

    Long listPush(String key, Object value);
    Long listUnshift(String key, Object value);
    List<Object> listFindAll(String key);
    Object listLPop(String key);

    void valuePut(String key, Object value);
    Object getValue(String key);
    void remove(String key);

    boolean expire(String key, long timeout, TimeUnit timeUnit);
}
