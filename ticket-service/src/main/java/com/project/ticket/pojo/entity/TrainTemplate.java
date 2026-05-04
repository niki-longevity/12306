package com.project.ticket.pojo.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TrainTemplate implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String trainCode;
    private String lineCode;
    private Integer businessCarriage;
    private Integer firstClassCarriage;
    private Integer secondClassCarriage;
}
