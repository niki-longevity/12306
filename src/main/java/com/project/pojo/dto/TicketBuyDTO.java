package com.project.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * 购票请求DTO
 * 接收前端购票的所有请求参数
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TicketBuyDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    // 一、核心必传信息
    private LocalDate date;          // 日期
    private String code;             // 车次编号
    private String startStation;     // 出发站
    private String endStation;       // 到达站
    private int seatType;            // 座位类型(0：一等座，1：二等座)

    // 二、业务必要信息
    private List<Passenger> passengerList; // 乘车人列表（数量=buyNum）

    // 乘车人子DTO（内部静态类，也可单独抽成外部类）
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Passenger implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String realName;      // 乘车人姓名
        private String idCard;        // 乘车人身份证号
    }
}