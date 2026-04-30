package com.project.ticket.pojo.enums;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeatTypeTest {

    @Test
    void fromCodeBusiness() {
        assertEquals(SeatType.BUSINESS, SeatType.fromCode(0));
    }

    @Test
    void fromCodeFirst() {
        assertEquals(SeatType.FIRST, SeatType.fromCode(1));
    }

    @Test
    void fromCodeSecond() {
        assertEquals(SeatType.SECOND, SeatType.fromCode(2));
    }

    @Test
    void fromCodeInvalid() {
        assertThrows(IllegalArgumentException.class, () -> SeatType.fromCode(99));
    }

    @Test
    void seatsPerCarriage() {
        assertEquals(5, SeatType.BUSINESS.getSeatsPerCarriage());
        assertEquals(28, SeatType.FIRST.getSeatsPerCarriage());
        assertEquals(90, SeatType.SECOND.getSeatsPerCarriage());
    }

    @Test
    void pricePerMile() {
        assertEquals(1.5, SeatType.BUSINESS.getPricePerMile());
        assertEquals(1.2, SeatType.FIRST.getPricePerMile());
        assertEquals(0.8, SeatType.SECOND.getPricePerMile());
    }

    @Test
    void cacheKey() {
        assertEquals("business", SeatType.BUSINESS.getCacheKey());
        assertEquals("firstClass", SeatType.FIRST.getCacheKey());
        assertEquals("secondClass", SeatType.SECOND.getCacheKey());
    }
}
