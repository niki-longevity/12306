package com.project.user.service;

import com.project.common.pojo.dto.UserLoginDTO;
import com.project.common.pojo.dto.UserRegisterDTO;
import com.project.common.pojo.vo.UserLoginVO;

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
