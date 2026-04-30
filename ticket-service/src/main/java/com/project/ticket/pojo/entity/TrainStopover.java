package com.project.ticket.pojo.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TrainStopover implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    // id
    private Long id;

    // 日期
    private LocalDate date;

    // 车次编号
    private String code;

    // 经停站
    private String stopoverStation;

    // 站序
    private Integer stationIndex;

    // 进站时间
    private LocalTime inTime;

    // 出站时间
    private LocalTime outTime;

    // 里程
    private Integer mileage;
}
