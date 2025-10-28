package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.management.Attribute;
import javax.servlet.http.HttpSession;

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

    @Override
    public Result sendcode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.校验不符合，返回错误信息
            return Result.fail("验证码错误！");
        }
        //3.校验通过 生成验证码
        String code=RandomUtil.randomNumbers(6);
        //4.保存验证码到session
        session.setAttribute("code",code);
        //5.发送验证码
        log.debug("短信验证码发送成功{}：",code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.校验不符合，返回错误信息
            return Result.fail("验证码错误！");
        }

        //3.校验验证码
        //用户发来的验证码
        Object cacheCode=session.getAttribute("code");
        //session中的验证码
        String code=loginForm.getCode();
        if(cacheCode==null||!cacheCode.toString().equals(code)){
            //4.不一致，报错
            return Result.fail("验证码错误！");
        }

        //5.一致，根据手机号查询用户 select * from tb_user where phone= ?
        User user=query().eq("phone",phone).one();
        //6.判断用户是否存在
        if(user==null){
            user=createUserWithPhone(phone);
        }
        //8.保存用户信息到session中
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
        }


    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(10));
        save(user);
        return user;
    }
}
