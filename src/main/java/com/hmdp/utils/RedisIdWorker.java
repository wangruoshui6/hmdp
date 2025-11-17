package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP=1735689600L;
    private static final int COUNT_BITS=32;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String KeyPrefix ) {
        //1.生成时间戳 代码的意思是当前时间到1970-1-1的秒数- 2025-1-1到1970-1-1的秒数 所得到的时间，为啥不直接减？为啥不直接用当前时间
        //减去2025-1-1的时间？  直接减还得重新转化为秒
        LocalDateTime now = LocalDateTime.now();
        long nowSeconds=now.toEpochSecond(ZoneOffset.UTC);
        long timestamp=nowSeconds-BEGIN_TIMESTAMP;
        //2.生成序列号
        //2.1获取当前日期，精确到天
        String data=now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2自增长
        long count =stringRedisTemplate.opsForValue().increment("icr:"+KeyPrefix+":"+data);
        //3.拼接并返回
        return timestamp <<COUNT_BITS|count;
    }
}
