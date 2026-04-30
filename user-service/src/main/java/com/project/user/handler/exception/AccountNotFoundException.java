package com.project.user.handler.exception;

import com.project.common.exception.BaseException;

public class AccountNotFoundException extends BaseException {
    public AccountNotFoundException() {
    }

    public AccountNotFoundException(String msg) {
        super(msg);
    }
}
