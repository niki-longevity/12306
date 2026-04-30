package com.project.user.handler.exception;

import com.project.common.exception.BaseException;

public class UsernameRepeatException extends BaseException {
    public UsernameRepeatException() {
    }

    public UsernameRepeatException(String msg) {
        super(msg);
    }
}
