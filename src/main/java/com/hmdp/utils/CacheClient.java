package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        String valueStr = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key, valueStr, time, timeUnit);
    }

    public void setWithLogicalTime(String key, Object value, Long time, TimeUnit timeUnit) {
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        String redisDataStr = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key, redisDataStr);
    }

    // 缓存穿透方法
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        //生成缓存key，根据KEY进行查询
        String key = keyPrefix + id;
        //从缓存中进行查询
        String json = stringRedisTemplate.opsForValue().get(key);
        //如果查询的不是null、'',说明缓存命中
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        //如果查询到的是不是null, 那么是空字符串, 查询到了缓存穿透的结果直接返回。
        if(json != null){
            return null;
        }
        //未命中，进行数据库访问，实现缓存重建
        R r = dbFallback.apply(id);

        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, timeUnit);

        return r;
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //使用逻辑时间解决缓存击穿
    public <R, ID> R queryWithLogicalExpire(String Prefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                            String lockKeyPrefix, Long time, TimeUnit timeUnit) {
        //1. 查询缓存
        String key = Prefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2. 如果缓存未命中返回空
        if (StrUtil.isBlank(json)) {
            return null;
        }
        //3. 缓存命中，获得缓存中封装的数据和过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4. 判断是否过期
        //4.1 如果没有过期，直接返回缓存数据
        if(LocalDateTime.now().isBefore(expireTime)) {
            return r;
        }
        //4.2 否则表示缓存过期，当前时间已经过了设置的逻辑时间
        String lockKey = lockKeyPrefix + id;
        //5. 请求互斥锁来进行缓存注入
        boolean isLock = tryLock(lockKey);
        if(isLock) {
            json = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(json, RedisData.class);
            data = (JSONObject) redisData.getData();
            r = JSONUtil.toBean(data, type);
            expireTime = redisData.getExpireTime();
            //4. 判断是否过期
            //4.1 如果没有过期，直接返回缓存数据
            if(LocalDateTime.now().isBefore(expireTime)) {
                return r;
            }
            //获得锁，利用线程池创建新的线程执行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //5.2 访问数据库，重建缓存
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalTime(key, r1, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //无论是否重建释放锁
                    unlock(lockKey);
                }
            });
        }
        //6. 否则表示没有获得锁，直接返回过期数据，因此采用逻辑时间的方法存在数据不一致的问题
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
