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
public class LineTrain implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    // id
    private Long id;

    // 干线编号
    private String lineCode;

    // 列车 code
    private String trainCode;
}
