package com.project.handler.exception;

import com.project.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 核心方法：捕获所有 BaseException 及其子类
     * 只要你在 Service 里 throw new BaseException("xxx")，就会进这里
     */
    @ExceptionHandler(BaseException.class)
    public Result exceptionHandler(BaseException ex){
        log.error("捕获到业务异常：{}", ex.getMessage());
        // 将异常里的 message (例如："密码错误") 取出来，放入 Result 返回给前端
        return Result.error(ex.getMessage());
    }

    /**
     * 兜底处理：处理所有意料之外的错误（比如空指针）
     */
    @ExceptionHandler(Exception.class)
    public Result exceptionHandler(Exception ex){
        log.error("未知错误：", ex);
        return Result.error("系统繁忙，请联系管理员");
    }
}