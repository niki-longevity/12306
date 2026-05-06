package com.project.user.service;

import com.project.common.pojo.dto.UserLoginDTO;
import com.project.common.pojo.dto.UserRegisterDTO;
import com.project.common.pojo.dto.ChangePasswordDTO;
import com.project.common.pojo.dto.UpdateProfileDTO;
import com.project.common.pojo.vo.UserLoginVO;
import com.project.common.pojo.vo.UserProfileVO;

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

    UserProfileVO getProfile(Long userId);
    void updateProfile(Long userId, UpdateProfileDTO dto);
    void changePassword(Long userId, ChangePasswordDTO dto);
}
