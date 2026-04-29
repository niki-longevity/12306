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
public class TrainTicketBitmap implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private LocalDate date;

    // 车次号
    private String code;

    // 车厢号
    private Integer carriageIndex;

    // 行号
    private Integer rowIndex;

    // 列号
    private Integer colIndex;

    // 二进制存储位图
    private byte[] bitmap;
}
