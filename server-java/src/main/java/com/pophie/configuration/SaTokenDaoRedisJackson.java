package com.pophie.configuration;

import cn.dev33.satoken.dao.SaTokenDao;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 自实现 SaTokenDao：复用 Spring Data Redis 的 StringRedisTemplate（不引入 jedis）。
 * Object 序列化使用私有 ObjectMapper（启用 default typing + LaissezFaireSubTypeValidator），
 * 与项目主 redisObjectMapper（不开 default typing）解耦，保证 sa-token 内部对象（SaSession 等）
 * 反序列化时类型信息完整，避免 ClassCastException。
 */
@Component
public class SaTokenDaoRedisJackson implements SaTokenDao {

    private final StringRedisTemplate srt;
    private final ObjectMapper saMapper;

    public SaTokenDaoRedisJackson(StringRedisTemplate srt) {
        this.srt = srt;
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        this.saMapper = om;
    }

    private void doSet(String key, String value, long timeout) {
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            srt.opsForValue().set(key, value);
        } else if (timeout == SaTokenDao.NOT_VALUE_EXPIRE || timeout <= 0) {
            // 不存
        } else {
            srt.opsForValue().set(key, value, timeout, TimeUnit.SECONDS);
        }
    }

    @Override
    public String get(String key) {
        return srt.opsForValue().get(key);
    }

    @Override
    public void set(String key, String value, long timeout) {
        doSet(key, value, timeout);
    }

    @Override
    public void update(String key, String value) {
        Long ttl = srt.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl == -2) return;
        if (ttl == -1) srt.opsForValue().set(key, value);
        else srt.opsForValue().set(key, value, ttl, TimeUnit.SECONDS);
    }

    @Override
    public void delete(String key) {
        srt.delete(key);
    }

    @Override
    public long getTimeout(String key) {
        Long t = srt.getExpire(key, TimeUnit.SECONDS);
        return t == null ? SaTokenDao.NOT_VALUE_EXPIRE : t;
    }

    @Override
    public void updateTimeout(String key, long timeout) {
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            srt.persist(key);
            return;
        }
        srt.expire(key, timeout, TimeUnit.SECONDS);
    }

    @Override
    public Object getObject(String key) {
        String raw = srt.opsForValue().get(key);
        if (raw == null) return null;
        try {
            return saMapper.readValue(raw, Object.class);
        } catch (Exception e) {
            throw new RuntimeException("sa-token getObject failed: " + key, e);
        }
    }

    @Override
    public void setObject(String key, Object object, long timeout) {
        try {
            doSet(key, saMapper.writeValueAsString(object), timeout);
        } catch (Exception e) {
            throw new RuntimeException("sa-token setObject failed: " + key, e);
        }
    }

    @Override
    public void updateObject(String key, Object object) {
        Long ttl = srt.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl == -2) return;
        try {
            String json = saMapper.writeValueAsString(object);
            if (ttl == -1) srt.opsForValue().set(key, json);
            else srt.opsForValue().set(key, json, ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("sa-token updateObject failed: " + key, e);
        }
    }

    @Override
    public void deleteObject(String key) {
        srt.delete(key);
    }

    @Override
    public long getObjectTimeout(String key) {
        Long t = srt.getExpire(key, TimeUnit.SECONDS);
        return t == null ? SaTokenDao.NOT_VALUE_EXPIRE : t;
    }

    @Override
    public void updateObjectTimeout(String key, long timeout) {
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            srt.persist(key);
            return;
        }
        srt.expire(key, timeout, TimeUnit.SECONDS);
    }

    @Override
    public List<String> searchData(String prefix, String keyword, int start, int size, boolean sortType) {
        String pattern = prefix + "*" + (keyword == null ? "" : keyword) + "*";
        Set<String> keys = srt.keys(pattern);
        if (keys == null || keys.isEmpty()) return Collections.emptyList();
        List<String> list = new ArrayList<>(keys);
        if (sortType) Collections.sort(list);
        else list.sort(Collections.reverseOrder());
        if (start >= list.size()) return Collections.emptyList();
        int end = (size == -1) ? list.size() : Math.min(start + size, list.size());
        return list.subList(start, end);
    }
}
