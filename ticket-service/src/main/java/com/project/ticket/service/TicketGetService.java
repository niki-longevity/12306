package com.project.ticket.service;

import com.project.ticket.pojo.dto.TicketListDTO;
import com.project.ticket.pojo.vo.TicketListVO;

import java.util.List;

public interface TicketGetService {
    /**
     * 查询车票
     * @param ticketListDTO
     * @return
     */
    List<TicketListVO> list(TicketListDTO ticketListDTO);
}
