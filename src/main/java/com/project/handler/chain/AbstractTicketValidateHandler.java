package com.project.handler.chain;

import com.project.utils.TicketValidateContext;

/**
 * 购票校验抽象处理器（责任链核心）
 */
public abstract class AbstractTicketValidateHandler {
    // 下一个处理器
    protected AbstractTicketValidateHandler nextHandler;

    /**
     * 设置下一个处理器
     */
    public AbstractTicketValidateHandler setNextHandler(AbstractTicketValidateHandler nextHandler) {
        this.nextHandler = nextHandler;
        return nextHandler; // 链式调用，方便组装
    }

    /**
     * 核心处理方法（子类实现具体校验逻辑）
     */
    public abstract void handle(TicketValidateContext context);
}