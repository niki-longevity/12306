package com.project.service;

import com.project.pojo.dto.UserLoginDTO;
import com.project.pojo.dto.UserRegisterDTO;
import com.project.pojo.vo.UserLoginVO;

public interface UserService {
    /**
     * 登录
     * @param userLoginDTO
     * @return
     */
    UserLoginVO login(UserLoginDTO userLoginDTO);

    /**
     * 注册
     * @param userRegisterDTO
     */
    void add(UserRegisterDTO userRegisterDTO);
}
