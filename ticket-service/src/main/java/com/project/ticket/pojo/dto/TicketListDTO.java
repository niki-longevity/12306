package com.project.ticket.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TicketListDTO implements Serializable {

    // 日期：yyyy-MM-dd
    private LocalDate date;

    // 始发站
    private String start;

    // 终点站
    private String end;
}
