package com.project.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TicketListVO implements Serializable { // 车次经停站静态信息 “缓存”

    // 日期
    private LocalDate date;

    // 车次编号
    private String code;

    // 乘客出发站
    private String start;

    // 乘客出发时间（即从经停站离开的时间 outTime）
    private LocalTime startTime;

    // 乘客目的站
    private String end;

    // 乘客目的时间（即到达经停站的时间 inTime）
    private LocalTime endTime;

    // 商务座余票
    private Integer businessNum;

    // 商务座票价格
    private Double businessPrice;

    // 一等座余票
    private Integer firstClassNum;

    // 一等座票价格
    private Double firstClassPrice;

    // 二等座余票
    private Integer secondClassNum;

    // 二等座票价格
    private Double secondClassPrice;
}
