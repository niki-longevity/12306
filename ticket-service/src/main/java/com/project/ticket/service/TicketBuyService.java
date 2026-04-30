package com.project.ticket.service;

import com.project.ticket.pojo.dto.TicketBuyDTO;

public interface TicketBuyService {
    /**
     * 购票
     * @param ticketBuyDTO
     * @return
     */
    String buy(TicketBuyDTO ticketBuyDTO);
}
