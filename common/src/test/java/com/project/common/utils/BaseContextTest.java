package com.project.common.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BaseContextTest {

    @Test
    void setAndGetCurrentId() {
        BaseContext.setCurrentId(123L);
        assertEquals(123L, BaseContext.getCurrentId());
        BaseContext.removeCurrentId();
    }

    @Test
    void removeClearsThreadLocal() {
        BaseContext.setCurrentId(456L);
        BaseContext.removeCurrentId();
        assertNull(BaseContext.getCurrentId());
    }
}
