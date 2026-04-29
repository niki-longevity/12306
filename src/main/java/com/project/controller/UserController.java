package com.project.controller;

import com.project.pojo.dto.UserLoginDTO;
import com.project.pojo.dto.UserRegisterDTO;
import com.project.pojo.vo.UserLoginVO;
import com.project.result.Result;
import com.project.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.security.auth.login.AccountNotFoundException;

@RestController
@Slf4j
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 登录
     * @param userLoginDTO
     * @return
     */
    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody UserLoginDTO userLoginDTO) throws AccountNotFoundException {
        log.info("用户登录：{}", userLoginDTO);
        UserLoginVO userLoginVO = userService.login(userLoginDTO);

        return Result.success(userLoginVO);
    }

    /**
     * 注册(新增)
     * @param userRegisterDTO
     * @return
     */
    @PostMapping("/add")
    public Result<String> add(@RequestBody UserRegisterDTO userRegisterDTO) {
        log.info("用户注册：{}", userRegisterDTO);
        userService.add(userRegisterDTO);

        return Result.success("注册成功！");
    }

}
