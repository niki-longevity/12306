package com.project.utils;

import com.project.pojo.bo.TicketListBO;
import com.project.pojo.dto.TicketBuyDTO;
import lombok.Data;

import java.time.LocalDate; /**
 * 购票校验上下文：封装校验参数、结果、错误信息
 */
@Data
public class TicketValidateContext {
    // 基础参数
    private TicketBuyDTO ticketBuyDTO;
    private LocalDate date;
    private String trainCode;
    private String startStation;
    private String endStation;
    private int seatType;
    private int passengerCount;

    // 中间结果（供后续处理器使用）
    private TicketListBO ticketListBO; // 缓存中的车次信息BO
    private boolean cacheLoaded = false; // 余票缓存是否已加载

    // 校验结果
    private boolean pass = true; // 是否通过校验
    private String errorMsg; // 错误信息
}
