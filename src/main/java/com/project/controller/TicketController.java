package com.project.controller;

import com.project.pojo.dto.TicketBuyDTO;
import com.project.pojo.dto.TicketListDTO;
import com.project.pojo.vo.TicketListVO;
import com.project.result.Result;
import com.project.service.TicketBuyService;
import com.project.service.TicketGetService;
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
        log.info("查询车票 ：{}", ticketListDTO);
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
        log.info("购买车票：{}", ticketBuyDTO);
        String result = ticketBuyService.buy(ticketBuyDTO);
        return Result.success(result);
    }

    // 前置校验……

    // 计算乘车人数量

    // 1、以 “Stock:date:trainCode:seatType” 为key，去 Redis 查库存，是一个hash结构，field表示区间，value表示库存

    // 2、查 jvm 缓存取站序，判断所有区间的库存，最小值为0则直接返回；
    // 否则获取key为“Token:date:trainCode”的令牌并扣减 1，若令牌数量为0，设置令牌数量为所有区间中 最大的库存值 - 1。

    // 3、查静态缓存，确定该列车+座位类型的车厢编组和经停站的站序

    // 4、去 Redis 取整个 "列车+座位类型" 的位图

    // 5、根据经停站站序生成区间（比站序少 1）二进制数据

    // 6、按车厢、排来for 循环搜索位图，与二进制数据进行位运算，搜索成功，则for的索引为对应的车厢、排、座位（多个for循环）

    // 7、加锁，先本地锁，再分布式锁
    // 本地锁：date:trainCode:车厢号:排号:座位号（如2026-03-03:G1:5:12:F），设置一个短过期时间
    // 分布式锁：date:trainCode:车厢号:排号:座位号，设置一个过期时间

    // 8、lua脚本先判断再修改位图，然后扣减库存，保证一致性，最后删除分布式锁和本地锁

    // 9、RocketMQ异步落库

    // 10、封装并返回结果

}
