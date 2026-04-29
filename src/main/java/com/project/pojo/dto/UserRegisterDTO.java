package com.project.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRegisterDTO implements Serializable {

    // 用户名
    private String username;

    // 密码
    private String password;

    // 手机号
    private String phone;

    // 真实姓名
    private String realName;

    // 身份证号
    private String idCard;
}
