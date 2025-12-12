package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.db.handler.RsHandler;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.management.Attribute;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendcode(String phone){
        //1.判断手机号是否正确
        if(RegexUtils.isPhoneInvalid(phone)){
            //如果不正确
            return Result.fail("手机号不正确！");
        }
        //2.正确 发送验证码
        String code=RandomUtil.randomNumbers(6);
        //3.将手机号和验证码存入Redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //4.发送验证码
        log.debug("短信验证码发送成功 {}",code);
        return Result.ok();

    }

  @Override
  public Result login(LoginFormDTO loginForm){
    //1.校验手机号
    String phone=loginForm.getPhone();
    if(RegexUtils.isPhoneInvalid(phone)){
        return Result.fail("手机号不正确！");
    }
    //2.获取验证码
    String code=loginForm.getCode();
    String cacheCode=stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
    if(cacheCode==null||!cacheCode.equals(code)){
        return Result.fail("验证码不正确！");
  }
  //3.获取用户
  User user=query().eq("phone",phone).one();
  if(user==null){
    user=createUserWithPhone(phone);
}  
    //4.生成token
    String token=UUID.randomUUID().toString(true);
    log.info("token:"+token);
    //将User对象转为HashMap存储
    UserDTO userDTO=BeanUtil.copyProperties(user,UserDTO.class);
    Map<String,Object> userMap=BeanUtil.beanToMap(userDTO,new HashMap<>(),
    CopyOptions.create().setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
    //存储
    String tokenKey=RedisConstants.LOGIN_USER_KEY+token;
    stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
    //设置token有效期
    stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);
    //返回token
    return Result.ok(token);
}

    @Override
    public Result sign() {
       //1.获取用户ID
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now=LocalDateTime.now();
        //3.拼接key
        String keySuffix=now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=USER_SIGN_KEY+userId+keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth=now.getDayOfMonth();
        //5.写入Redis setbit key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取用户ID
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:1010:202512 GET u12 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result==null||result.isEmpty()){
            //没有任何签到结果
            return Result.ok(0);
        }
        Long num=result.get(0);
        if (num==null||num==0){
            return Result.ok(0);
        }
        //6.循环遍历
        int count=0;
        while (true){
            //6.1让这个数字与1做与运算，得到数字的最后一个bit位 判断这个bit位是否为0
            //与运算是从二进制中后面的位数开始运算
            if ((num&1)==0){
                //如果为0，说明未签到，结束
                break;
            }else {
                //如果不为0，说明已经签到 计数器+1
                count++;
            }
            //把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num>>>=1;
        }
        return Result.ok(count);
    }
    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(10));
        save(user);
        return user;
    }
}
