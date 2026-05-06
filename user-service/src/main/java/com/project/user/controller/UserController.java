package com.project.user.controller;

import com.project.common.pojo.dto.UserLoginDTO;
import com.project.common.pojo.dto.UserRegisterDTO;
import com.project.common.pojo.dto.UpdateProfileDTO;
import com.project.common.pojo.dto.ChangePasswordDTO;
import com.project.common.pojo.vo.UserLoginVO;
import com.project.common.pojo.vo.UserProfileVO;
import com.project.common.result.Result;
import com.project.common.utils.BaseContext;
import com.project.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.security.auth.login.AccountNotFoundException;

@CrossOrigin(origins = "*")
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

    @GetMapping("/profile")
    public Result<UserProfileVO> profile() {
        Long userId = BaseContext.getCurrentId();
        return Result.success(userService.getProfile(userId));
    }

    @PutMapping("/profile")
    public Result<String> updateProfile(@RequestBody UpdateProfileDTO dto) {
        Long userId = BaseContext.getCurrentId();
        userService.updateProfile(userId, dto);
        return Result.success("修改成功");
    }

    @PutMapping("/profile/password")
    public Result<String> changePassword(@RequestBody ChangePasswordDTO dto) {
        Long userId = BaseContext.getCurrentId();
        userService.changePassword(userId, dto);
        return Result.success("密码修改成功");
    }

}
