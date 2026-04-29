package com.project.handler.exception;

public class TicketNotFoundException extends BaseException {
    public TicketNotFoundException() {
    }

    public TicketNotFoundException(String msg) {
        super(msg);
    }
}
