package com.project.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("orders")
public class Order implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
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

    // Lua rollback context
    private Integer startSection;
    private Integer endSection;
    private Integer totalSectionCount;
    private Integer passengerCount;
    private String sectionsJson;
    private Long seatStartBit;
}
