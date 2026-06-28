package com.pophie.base;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pophie.exception.HttpRequestException;
import com.pophie.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * 业务 Service 基类。
 * E 实体类（继承 BaseEntity）
 * R 仓库接口（继承 BaseRepository<E>）
 *
 * 时间字段（createTime / updateTime）由 BaseEntity 的 @PrePersist / @PreUpdate 自动维护，
 * BaseService.save / saveAll 无需手动赋值。
 */
public class BaseService<E extends BaseEntity, R extends BaseRepository<E>> {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    protected StringRedisTemplate stringRedisTemplate;

    @Autowired
    @Qualifier("redisObjectMapper")
    protected ObjectMapper objectMapper;

    @Autowired
    private R dao;

    public R getDao() { return dao; }

    @Transactional
    public E save(E entity) {
        return dao.save(entity);
    }

    @Transactional
    public Iterable<E> saveAll(Iterable<E> entities) {
        return dao.saveAll(entities);
    }

    public Pageable getPageable(int page, int pageSize) {
        return getPageable(page, pageSize, Sort.by(Sort.Direction.DESC, "createTime"));
    }

    public Pageable getPageable(int page, int pageSize, Sort sort) {
        int p = page > 0 ? page - 1 : 0;
        int size = Math.min(pageSize, Constants.MAX_PAGE_COUNT);
        if (size <= 0) size = 10;
        return PageRequest.of(p, size, sort);
    }

    public void checkParams(String... params) {
        for (String p : params) {
            if (!StringUtils.hasText(p)) throw new HttpRequestException("缺少必要参数");
        }
    }

    public void cacheData(Object data, String key, long timeout, TimeUnit unit) {
        try {
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(data), timeout, unit);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("cache write failed: " + key, e);
        }
    }

    public <T> T readCacheFirst(String key, long timeout, TimeUnit unit,
                                ICacheCallback<T> callback, Class<T> clazz) throws Throwable {
        String value = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.hasText(value)) {
            return objectMapper.readValue(value, clazz);
        }
        if (callback == null) return null;
        T fresh = callback.loadNewData(key);
        if (fresh != null) cacheData(fresh, key, timeout, unit);
        return fresh;
    }

    public interface ICacheCallback<T> {
        T loadNewData(String key) throws Throwable;
    }
}
