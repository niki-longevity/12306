package com.project.ticket.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StationVO {
    private String stationName;
    private String city;
    private String province;
    private String pinyinAbbr;
}
