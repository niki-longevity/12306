package com.project.user.utils;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    @BeforeAll
    static void setup() {
        System.setProperty("JWT_SECRET", "test-secret-key-for-unit-tests-minimum-256bits!");
    }

    @Test
    void generateAndParseToken() {
        String token = JwtUtil.generateToken(1L, "testuser");
        assertNotNull(token);
        assertEquals(1L, JwtUtil.getUserId(token));
        assertEquals("testuser", JwtUtil.getUsername(token));
    }

    @Test
    void validateValidToken() {
        String token = JwtUtil.generateToken(2L, "user2");
        assertTrue(JwtUtil.validateToken(token));
    }

    @Test
    void validateNullToken() {
        assertFalse(JwtUtil.validateToken(null));
    }

    @Test
    void validateEmptyToken() {
        assertFalse(JwtUtil.validateToken(""));
    }

    @Test
    void validateTamperedToken() {
        String token = JwtUtil.generateToken(3L, "user3");
        assertFalse(JwtUtil.validateToken(token + "x"));
    }
}
