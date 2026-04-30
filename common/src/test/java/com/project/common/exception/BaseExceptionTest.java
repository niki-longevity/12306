package com.project.common.exception;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BaseExceptionTest {

    @Test
    void baseExceptionStoresMessage() {
        BaseException ex = new BaseException("test error");
        assertEquals("test error", ex.getMessage());
    }

    @Test
    void subclassInheritsMessage() {
        BaseException ex = new BaseException("sub error") {};
        assertEquals("sub error", ex.getMessage());
    }
}
