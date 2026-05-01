package com.project.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.pojo.entity.Order;
import com.project.order.mapper.SeatBitmapMapper;
import com.project.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(
        topic = "order-close-topic",
        consumerGroup = "order-close-consumer-group"
)
public class OrderCloseConsumer implements RocketMQListener<String> {

    private final OrderService orderService;
    private final ObjectMapper objectMapper;
    private final SeatBitmapMapper seatBitmapMapper;
    private final StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> REFUND_LUA;
    static {
        REFUND_LUA = new DefaultRedisScript<>();
        REFUND_LUA.setLocation(new org.springframework.core.io.ClassPathResource("lua/ticket_refund.lua"));
        REFUND_LUA.setResultType(Long.class);
    }

    @Override
    public void onMessage(String message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(message, Map.class);
            String trainCode = payload.get("trainCode").toString();
            LocalDate date = LocalDate.parse(payload.get("date").toString());
            int seatType = Integer.parseInt(payload.get("seatType").toString());
            int carriageNum = Integer.parseInt(payload.get("carriageNum").toString());
            int seatNum = Integer.parseInt(payload.get("seatNum").toString());
            int startSection = Integer.parseInt(payload.get("startSection").toString());
            int endSection = Integer.parseInt(payload.get("endSection").toString());
            int totalSectionCount = Integer.parseInt(payload.get("totalSectionCount").toString());
            long seatStartBit = Long.parseLong(payload.get("seatStartBit").toString());

            // Build close mask
            int byteLen = (totalSectionCount + 7) / 8;
            byte[] mask = new byte[byteLen];
            for (int s = startSection; s <= endSection; s++) {
                int bitPos = s - 1;
                mask[bitPos / 8] |= (1 << (bitPos % 8));
            }

            // 1. Clear MySQL bitmap
            seatBitmapMapper.clearBitmap(trainCode, date, seatType, carriageNum, seatNum, mask);

            // 2. Rollback Redis (bitmap + stock + token)
            String bitmapKey = String.format("%s:%s:%d:bitmap", date, trainCode, seatType);
            String stockKey = String.format("Stock:%s:%s:%d", date, trainCode, seatType);
            String tokenKey = String.format("Token:%s:%s:%d", date, trainCode, seatType);
            int passengerCount = Integer.parseInt(payload.get("passengerCount").toString());
            String sectionsJson = payload.get("sectionsJson").toString();

            stringRedisTemplate.execute(REFUND_LUA,
                    Arrays.asList(bitmapKey, stockKey, tokenKey),
                    String.valueOf(seatStartBit),
                    String.valueOf(startSection),
                    String.valueOf(endSection),
                    String.valueOf(totalSectionCount),
                    String.valueOf(passengerCount),
                    sectionsJson
            );

            log.info("延时关单+回滚完成: train={}, seat={}/{}", trainCode, carriageNum, seatNum);
        } catch (Exception e) {
            log.error("关单处理失败", e);
        }
    }
}
