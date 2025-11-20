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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.management.Attribute;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
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



    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(10));
        save(user);
        return user;
    }
}
