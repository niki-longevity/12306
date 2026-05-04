package com.project.ticket.pojo.entity;

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
public class ScheduleWindow implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Integer id;
    private LocalDate windowStart;
    private LocalDate windowEnd;
}
