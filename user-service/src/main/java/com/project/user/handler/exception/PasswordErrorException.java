package com.project.user.handler.exception;

import com.project.common.exception.BaseException;

public class PasswordErrorException extends BaseException {
    public PasswordErrorException() {
    }

    public PasswordErrorException(String msg) {
        super(msg);
    }
}
