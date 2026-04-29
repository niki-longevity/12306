package com.project.pojo.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UsernamePhone implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    // 用户名
    private String username;

    // 手机号
    private String phone;
}