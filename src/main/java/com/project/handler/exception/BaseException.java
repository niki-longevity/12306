package com.project.handler.exception;

/**
 * 业务异常根类
 * 所有我们自己手动抛出的逻辑错误，都继承这个类
 */
public class BaseException extends RuntimeException {

    public BaseException() {
    }

    public BaseException(String msg) {
        super(msg);
    }
}