package com.pophie.redis;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service("redisHelper")
public class RedisHelperImpl implements RedisHelper {

    private final RedisTemplate<String, Object> redisTemplate;
    private final HashOperations<String, String, Object> hashOps;
    private final ListOperations<String, Object> listOps;
    private final ZSetOperations<String, Object> zsetOps;
    private final SetOperations<String, Object> setOps;
    private final ValueOperations<String, Object> valueOps;

    @Autowired
    public RedisHelperImpl(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.hashOps = redisTemplate.opsForHash();
        this.listOps = redisTemplate.opsForList();
        this.zsetOps = redisTemplate.opsForZSet();
        this.setOps = redisTemplate.opsForSet();
        this.valueOps = redisTemplate.opsForValue();
    }

    @Override
    public void hashPut(String key, String hashKey, Object value) {
        hashOps.put(key, hashKey, value);
    }

    @Override
    public Map<String, Object> hashFindAll(String key) {
        return hashOps.entries(key);
    }

    @Override
    public Object hashGet(String key, String hashKey) {
        return hashOps.get(key, hashKey);
    }

    @Override
    public void hashRemove(String key, String hashKey) {
        hashOps.delete(key, hashKey);
    }

    @Override
    public Long listPush(String key, Object value) {
        return listOps.rightPush(key, value);
    }

    @Override
    public Long listUnshift(String key, Object value) {
        return listOps.leftPush(key, value);
    }

    @Override
    public List<Object> listFindAll(String key) {
        Boolean exists = redisTemplate.hasKey(key);
        if (exists == null || !exists) return null;
        Long size = listOps.size(key);
        if (size == null || size == 0) return null;
        return listOps.range(key, 0, size);
    }

    @Override
    public Object listLPop(String key) {
        return listOps.leftPop(key);
    }

    @Override
    public void valuePut(String key, Object value) {
        valueOps.set(key, value);
    }

    @Override
    public Object getValue(String key) {
        return valueOps.get(key);
    }

    @Override
    public void remove(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public boolean expire(String key, long timeout, TimeUnit timeUnit) {
        Boolean ok = redisTemplate.expire(key, timeout, timeUnit);
        return Boolean.TRUE.equals(ok);
    }
}
