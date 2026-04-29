package com.project.pojo.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class User implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    // 用户id
    private Long id;

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

    // 创建时间
    private LocalDateTime createTime;

}