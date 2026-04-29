package com.project.service;

import com.project.pojo.dto.TicketListDTO;
import com.project.pojo.vo.TicketListVO;

import java.util.List;

public interface TicketGetService {
    /**
     * 查询车票
     * @param ticketListDTO
     * @return
     */
    List<TicketListVO> list(TicketListDTO ticketListDTO);
}
