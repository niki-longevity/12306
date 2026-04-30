package com.project.ticket.controller;

import com.project.ticket.pojo.dto.TicketBuyDTO;
import com.project.ticket.pojo.dto.TicketListDTO;
import com.project.ticket.pojo.vo.TicketListVO;
import com.project.common.result.Result;
import com.project.ticket.service.TicketBuyService;
import com.project.ticket.service.TicketGetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/ticket")
public class TicketController {

    @Autowired
    private TicketGetService ticketGetService;

    @Autowired
    private TicketBuyService ticketBuyService;

    /**
     * 查询 指定日期、指定出发站和到达站 的所有车次的票信息
     * @param ticketListDTO
     * @return
     */
    @GetMapping("/list")
    public Result<List<TicketListVO>> list(TicketListDTO ticketListDTO) {
        log.info("查询车票 :{}", ticketListDTO);
        List<TicketListVO> trainTicketVOS = ticketGetService.list(ticketListDTO);
        return Result.success(trainTicketVOS);
    }

    /**
     * 购买车票
     * @param ticketBuyDTO
     * @return
     */
    @PutMapping("/buy")
    public Result<String> buy(@RequestBody TicketBuyDTO ticketBuyDTO) {
        log.info("购买车票:{}", ticketBuyDTO);
        return ticketBuyService.buy(ticketBuyDTO);
    }
}
