package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
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

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));
        //写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key=keyPrefix+id;
        //1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(json)){
            //3.存在，直接返回
            return JSONUtil.toBean(json,type);
        }
        //判断命中是否是空值
        if(json!=null){
            return null;
        }
        //4.不存在，根据id查询数据库
        R r =dbFallback.apply(id);
        //5.不存在，返回错误
        if(r==null){
            //添加随机值避免缓存雪崩
            Long randomTTL= RandomUtil.randomLong(0,5);
            //将空值写入Redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL+randomTTL,TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入redis,并设置过期时间,添加随机值避免缓存雪崩
        this.set(key,r,time,unit);
        //7.返回
        return r;
    }


    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix,ID id,Class<R>type,Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key=keyPrefix+id;
        //1.从redis查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isBlank(Json)){
            //3.不存在，直接返回
            return null;
        }
        //4.命中，需要先把json反序列化为对象
        RedisData redisData= JSONUtil.toBean(Json, RedisData.class);
        R r=JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime=redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，返回店铺信息
            return r;
        }
        //5.2过期,需要缓存重建

        //6.缓存重建
        //6.1获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock=tryLock(lockKey);
        //6.2判断是否获取锁成功
        if(isLock){
            // ✅ Double Check：再次检查缓存
            String cacheValue = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(cacheValue)) {
                RedisData checkData = JSONUtil.toBean(cacheValue, RedisData.class);
                LocalDateTime checkExpireTime = checkData.getExpireTime();
                if (checkExpireTime.isAfter(LocalDateTime.now())) {
                    // 缓存已经被其他线程重建了，直接返回，不重建
                    unlock(lockKey);
                    return JSONUtil.toBean((JSONObject) checkData.getData(),type);
                }
            }

            //6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                //重建缓存
                try {
                    //查询数据库
                    R r1=dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                finally {
                    //释放锁
                    unlock(lockKey);
                }

            });
        }

        //6.4返回过期的商铺信息
        //7.返回
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
//    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
//        //1.查询店铺数据
//        Shop shop=getById(id);
//        Thread.sleep(100);
//        //2.封装逻辑过期时间
//        RedisData redisData=new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        //3.写入Redis,不设置物理过期时间，这样物理过期时间就是永久有效，逻辑过期时间就是我们自己设置的时间
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
//    }
}
