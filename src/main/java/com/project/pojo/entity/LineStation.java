package com.project.pojo.entity;

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
public class LineStation implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    // id
    private Long id;

    // 干线编号
    private String lineCode;

    // 干线途经的车站
    private String station;

    // 里程
    private Integer mileage;
}
