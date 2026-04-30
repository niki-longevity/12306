package com.project.common.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class UserLoginVO implements Serializable {

    // 用户id
    private Long id;

    // 用户名
    private String username;

    // token令牌
    private String token;
}
