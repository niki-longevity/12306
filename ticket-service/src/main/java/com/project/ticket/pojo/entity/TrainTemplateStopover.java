package com.project.ticket.pojo.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TrainTemplateStopover implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String trainCode;
    private String stationName;
    private Integer stationIndex;
    private LocalTime inTime;
    private LocalTime outTime;
    private Integer mileage;
}
