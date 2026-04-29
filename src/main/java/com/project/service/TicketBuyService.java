package com.project.service;

import com.project.pojo.dto.TicketBuyDTO;

public interface TicketBuyService {
    /**
     * 购票
     * @param ticketBuyDTO
     * @return
     */
    String buy(TicketBuyDTO ticketBuyDTO);
}
