package com.project.user.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoginIdentityUtilsTest {

    @Test
    void judgePhoneNumber() {
        assertEquals(LoginType.PHONE, LoginIdentityUtils.judgeLoginType("13800138000"));
    }

    @Test
    void judgeUsername() {
        assertEquals(LoginType.USERNAME, LoginIdentityUtils.judgeLoginType("testuser"));
    }

    @Test
    void isPhoneReturnsTrue() {
        assertTrue(LoginIdentityUtils.isPhone("15912345678"));
    }

    @Test
    void isPhoneReturnsFalseForUsername() {
        assertFalse(LoginIdentityUtils.isPhone("myusername"));
    }

    @Test
    void isPhoneReturnsFalseForNull() {
        assertFalse(LoginIdentityUtils.isPhone(null));
    }

    @Test
    void emptyLoginIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                LoginIdentityUtils.judgeLoginType(null));
    }
}
