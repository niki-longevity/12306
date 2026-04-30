package com.project.ticket.preload;

import com.project.ticket.cache.warmup.TrainStopCacheLoader;
import com.project.ticket.pojo.bo.TicketListBO;
import com.project.ticket.pojo.enums.SeatType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * TEST-ONLY: Preload token bucket values into Redis.
 * Token = (seats per carriage x carriageCount) x sectionCount
 */
@Slf4j
@Component("tokenPreloader")
@RequiredArgsConstructor
public class TokenPreloader {

    private final StringRedisTemplate stringRedisTemplate;
    private final TrainStopCacheLoader cacheLoader;

    public void preload(LocalDate date, String trainCode) {
        try {
            String cacheKey = date + ":" + trainCode;
            TicketListBO bo = (TicketListBO) cacheLoader.load(cacheKey);
            if (bo == null) {
                log.warn("Train {} has no data, skipping token preload", cacheKey);
                return;
            }
            int sectionCount = bo.getStopoverStations().size() - 1;
            if (sectionCount <= 0) return;

            int business = countSeats(bo.getBusinessCarriageInfo(), SeatType.BUSINESS.getSeatsPerCarriage());
            int first = countSeats(bo.getFirstClassCarriageInfo(), SeatType.FIRST.getSeatsPerCarriage());
            int second = countSeats(bo.getSecondClassCarriageInfo(), SeatType.SECOND.getSeatsPerCarriage());

            setToken(date, trainCode, 0, business * sectionCount);
            setToken(date, trainCode, 1, first * sectionCount);
            setToken(date, trainCode, 2, second * sectionCount);

            log.info("Token preload done: {} business={} first={} second={}",
                    cacheKey, business * sectionCount, first * sectionCount, second * sectionCount);
        } catch (Exception e) {
            log.error("Token preload failed: {}/{}", date, trainCode, e);
        }
    }

    private void setToken(LocalDate date, String trainCode, int seatType, int tokenCount) {
        String tokenKey = String.format("Token:%s:%s:%d", date, trainCode, seatType);
        stringRedisTemplate.opsForValue().set(tokenKey, String.valueOf(tokenCount));
    }

    private int countSeats(TicketListBO.CarriageInfo info, int seatsPerCarriage) {
        if (info == null || info.getCarriageIndexes() == null) return 0;
        return info.getCarriageIndexes().size() * seatsPerCarriage;
    }
}
