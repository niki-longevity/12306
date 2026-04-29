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
public class TrainCarriage implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    // id
    private Long id;

    // 车次编号
    private String trainCode;

    // 商务车厢数
    private Integer businessCarriage;

    // 一等车厢数
    private Integer firstClassCarriage;

    // 二等车厢数
    private Integer secondClassCarriage;
}
