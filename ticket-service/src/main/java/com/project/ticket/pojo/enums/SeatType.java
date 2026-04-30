package com.project.ticket.pojo.enums;

import lombok.Getter;

@Getter
public enum SeatType {
    BUSINESS(0, "商务座", 5, 1.5),
    FIRST(1, "一等座", 28, 1.2),
    SECOND(2, "二等座", 90, 0.8);

    private final int code;
    private final String label;
    private final int seatsPerCarriage;
    private final double pricePerMile;

    SeatType(int code, String label, int seatsPerCarriage, double pricePerMile) {
        this.code = code;
        this.label = label;
        this.seatsPerCarriage = seatsPerCarriage;
        this.pricePerMile = pricePerMile;
    }

    public static SeatType fromCode(int code) {
        for (SeatType type : values()) {
            if (type.code == code) return type;
        }
        throw new IllegalArgumentException("Unknown seat type: " + code);
    }

    public String getCacheKey() {
        return switch (this) {
            case BUSINESS -> "business";
            case FIRST -> "firstClass";
            case SECOND -> "secondClass";
        };
    }
}
