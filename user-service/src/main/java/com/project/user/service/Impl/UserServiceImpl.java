package com.project.user.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.project.user.handler.exception.AccountNotFoundException;
import com.project.user.handler.exception.PasswordErrorException;
import com.project.user.handler.exception.UsernameRepeatException;
import com.project.user.mapper.UserMapper;
import com.project.user.mapper.UsernamePhoneMapper;
import com.project.common.pojo.dto.UserLoginDTO;
import com.project.common.pojo.dto.UserRegisterDTO;
import com.project.common.pojo.entity.User;
import com.project.common.pojo.entity.UsernamePhone;
import com.project.common.pojo.vo.UserLoginVO;
import com.project.user.service.UserService;
import com.project.user.utils.JwtUtil;
import com.project.user.utils.LoginIdentityUtils;
import com.project.user.utils.LoginType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final RBloomFilter<String> usernameBloomFilter;
    private final UsernamePhoneMapper usernamePhoneMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 登录
     *
     * @param userLoginDTO
     * @return
     */
    @Override
    public UserLoginVO login(UserLoginDTO userLoginDTO) {
        String loginId = userLoginDTO.getLoginId();

        // 1. 判断登录类型（优先手机号）
        LoginType loginType = LoginIdentityUtils.judgeLoginType(loginId);

        // 2. 根据类型查询用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (LoginType.PHONE.equals(loginType)) {
            // 直接根据分片键 phone 查询
            wrapper.eq(User::getPhone, loginId);
        } else {
            // 查询路由表，取出分片键 phone，避免读扩散
            LambdaQueryWrapper<UsernamePhone> wrapper1 = new LambdaQueryWrapper<>();
            wrapper1.eq(UsernamePhone::getUsername, loginId);
            UsernamePhone usernamePhone = usernamePhoneMapper.selectOne(wrapper1);
            if (usernamePhone == null) {
                throw new AccountNotFoundException("手机号或密码错误");
            }
            wrapper.eq(User::getPhone, usernamePhone.getPhone());
        }
        User user = userMapper.selectOne(wrapper);

        // 3. 判断账号是否存在
        if (user == null) {
            // 抛出异常，会被全局异常处理器捕获，返回给前端 "账号不存在"
            throw new AccountNotFoundException("手机号或密码错误");
            // 如果没有定义细分异常，直接用 throw new BaseException("账号不存在");
        }

        // 4. 校验密码（BCrypt）
        if (!passwordEncoder.matches(userLoginDTO.getPassword(), user.getPassword())) {
            throw new PasswordErrorException("手机号或密码错误");
        }

        // 5. 生成 JWT token
        String token = JwtUtil.generateToken(user.getId(), user.getUsername());

        // 6. 封装返回结果 VO
        return UserLoginVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .token(token)
                .build();

    }

    /**
     * 注册
     * @param userRegisterDTO
     */
    @Override
    public void add(UserRegisterDTO userRegisterDTO) {
        String username = userRegisterDTO.getUsername();

        // 布隆过滤器判断用户名是否已存在
        if (usernameBloomFilter.contains(username)) {
            throw new UsernameRepeatException("用户名已存在，无法注册");
        }

        User user = new User();
        BeanUtils.copyProperties(userRegisterDTO, user);
        // BCrypt 加密密码
        user.setPassword(passwordEncoder.encode(userRegisterDTO.getPassword()));
        user.setCreateTime(LocalDateTime.now());

        // 插入数据库
        userMapper.insert(user);

        // 用户名加入布隆过滤器
        usernameBloomFilter.add(username);

        // 插入用户名-手机号路由表
        UsernamePhone up = new UsernamePhone();
        up.setUsername(username);
        up.setPhone(user.getPhone());
        usernamePhoneMapper.insert(up);
    }
}
