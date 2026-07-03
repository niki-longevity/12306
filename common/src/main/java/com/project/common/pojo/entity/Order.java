package com.project.common.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("orders")
public class Order implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long userId;
    private LocalDate date;
    private String trainCode;
    private String startStation;
    private String endStation;
    private Integer seatType;
    private Integer carriageNum;
    private Integer seatNum;
    private String status;       // UNPAID, PAID, CANCELLED, EXPIRED
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // Payment
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String paymentNo;    // 模拟交易号(非持久化)

    // 发车时间(非持久化，查询时填充，用于前端判断是否已发车)
    @JsonFormat(pattern = "HH:mm:ss")
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private LocalTime departureTime;

    // Lua rollback context
    private Integer startSection;
    private Integer endSection;
    private Integer totalSectionCount;
    private Integer passengerCount;
    private String sectionsJson;
    private Long seatStartBit;
}
