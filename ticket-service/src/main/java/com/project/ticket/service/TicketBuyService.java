package com.project.ticket.service;

import com.project.common.result.Result;
import com.project.ticket.pojo.dto.TicketBuyDTO;

public interface TicketBuyService {
    /**
     * 购票
     * @return Result.success("排队中") 或 Result.error("原因")
     */
    Result<String> buy(TicketBuyDTO ticketBuyDTO);
}
