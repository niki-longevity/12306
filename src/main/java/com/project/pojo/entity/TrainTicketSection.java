package com.project.pojo.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TrainTicketSection implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private LocalDate date;

    private String code;

    // 区间序号
    private Integer sectionIndex;

    // 商务座余票
    private Integer businessSeat;

    // 一等座余票
    private Integer firstSeat;

    // 二等座余票
    private Integer secondSeat;
}
