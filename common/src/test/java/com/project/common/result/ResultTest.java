package com.project.common.result;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultTest {

    @Test
    void successWithoutData() {
        Result<Void> r = Result.success();
        assertEquals(1, r.getCode());
        assertNull(r.getMsg());
        assertNull(r.getData());
    }

    @Test
    void successWithData() {
        Result<String> r = Result.success("hello");
        assertEquals(1, r.getCode());
        assertEquals("hello", r.getData());
    }

    @Test
    void error() {
        Result<Void> r = Result.error("something wrong");
        assertEquals(0, r.getCode());
        assertEquals("something wrong", r.getMsg());
    }
}
