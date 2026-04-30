package com.project.user.utils;

import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 登录标识（手机号/用户名）判断工具类
 */
public class LoginIdentityUtils {

    /**
     * 国内手机号正则表达式（严格匹配）
     * 规则：11位数字，以1开头，第二位为3-9（覆盖所有运营商号段）
     */
    private static final String PHONE_REGEX = "^1[3-9]\\d{9}$";
    private static final Pattern PHONE_PATTERN = Pattern.compile(PHONE_REGEX);

    /**
     * 判断登录输入的是手机号还是用户名（优先判断手机号）
     * @param loginId 登录输入的字符串（手机号/用户名）
     * @return 登录类型枚举（PHONE/USERNAME）
     * @throws IllegalArgumentException 空值/空白字符时抛出
     */
    public static LoginType judgeLoginType(String loginId) {
        // 1. 基础空值校验
        if (!StringUtils.hasText(loginId)) {
            throw new IllegalArgumentException("登录标识不能为空");
        }
        String trimLoginId = loginId.trim();

        // 2. 优先匹配手机号正则
        if (PHONE_PATTERN.matcher(trimLoginId).matches()) {
            return LoginType.PHONE;
        }

        // 3. 不匹配手机号则判定为用户名
        return LoginType.USERNAME;
    }

    /**
     * 简化版：直接返回是否为手机号（适用于只需判断手机号的场景）
     * @param loginId 登录输入的字符串
     * @return true=手机号，false=非手机号（用户名）
     */
    public static boolean isPhone(String loginId) {
        if (!StringUtils.hasText(loginId)) {
            return false;
        }
        return PHONE_PATTERN.matcher(loginId.trim()).matches();
    }
}
