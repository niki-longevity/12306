package com.project.ticket.pojo.bo;

import lombok.*;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TicketListBO implements Serializable { // 车次经停站+查票库存静态信息 “缓存”

    @Serial
    private static final long serialVersionUID = 1L;

    // 基础字段
    private LocalDate date;
    private String code; // 车次编号（如G123）
    private String startStation; // 总起始站
    private String endStation; // 总终点站
    private List<StopoverStation> stopoverStations; // 经停站（19个区间对应20个站，19个StopoverStation）

    // ========== 新增：按区间的座位库存状态（查票核心） ==========
    // Key：座位类型_区间号（如business_2、firstClass_5、secondClass_19）
    // Value：该座位类型在该区间的库存状态
    private Map<String, IntervalStockStatus> intervalStockMap;

    // ========== 保留：静态车厢信息（仅展示，无库存） ==========
    private CarriageInfo businessCarriageInfo; // 商务座车厢信息
    private CarriageInfo firstClassCarriageInfo; // 一等座车厢信息
    private CarriageInfo secondClassCarriageInfo; // 二等座车厢信息

    // ========== 内部类：经停站（无修改） ==========
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StopoverStation implements Serializable {
        private String stopoverStation; // 经停站名称
        private Integer stationIndex; // 站序（1-20）
        private LocalTime inTime; // 进站时间
        private LocalTime outTime; // 出站时间
        private Integer mileage; // 里程
    }

    // ========== 内部类：车厢信息（仅保留静态字段，删除stock） ==========
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CarriageInfo implements Serializable {
        private String carriageType; // 车厢类型：商务座车厢/一等座车厢/二等座车厢
        private List<Integer> carriageIndexes; // 车厢序号列表（如[3,4,5,6,7,8]）
    }

    // ========== 新增内部类：区间库存状态（查票核心） ==========
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class IntervalStockStatus implements Serializable {
        private Integer statusCode; // 状态码（0无票/1低库存/2充足，关联枚举）
        private Integer realStock; // 真实库存数（低库存时存具体值，充足时存-1，无票存0）
    }

    // ========== 枚举：座位状态（仅映射code和含义，不可修改枚举本身） ==========
    public enum SeatStatus {
        NO_TICKET(0, "无票"),
        LOW_STOCK(1, "低库存"),
        SUFFICIENT_STOCK(2, "库存充足");

        @Getter
        private final Integer code; // 状态码（固定）
        @Getter
        private final String desc; // 状态描述

        SeatStatus(Integer code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        // 根据code快速获取枚举（用于解析状态）
        public static SeatStatus getByCode(Integer code) {
            for (SeatStatus status : values()) {
                if (status.code.equals(code)) {
                    return status;
                }
            }
            return SUFFICIENT_STOCK; // 默认充足
        }
    }
}