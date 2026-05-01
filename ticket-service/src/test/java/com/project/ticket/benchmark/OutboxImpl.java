package com.project.ticket.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.utils.BaseContext;
import com.project.ticket.mapper.TicketOutboxMapper;
import com.project.ticket.pojo.dto.TicketBuyDTO;
import com.project.ticket.handler.builder.TicketValidateChainBuilder;
import com.project.ticket.pojo.entity.TicketOutbox;
import com.project.ticket.service.impl.TicketBuyServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RedissonClient;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Outbox variant: INSERT ticket_outbox + RocketMQ delayed close.
 * Active only with profile "bench-outbox".
 */
@Slf4j
@Service
@Profile("bench-outbox")
public class OutboxImpl extends TicketBuyServiceImpl {

    private final TicketOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final RocketMQTemplate rocketMQTemplate;

    public OutboxImpl(StringRedisTemplate str, RedissonClient rc, CacheManager cm, ObjectMapper om,
                      TicketValidateChainBuilder cb, RocketMQTemplate rt,
                      TicketOutboxMapper outboxMapper) {
        super(str, rc, cm, om, cb, rt);
        this.outboxMapper = outboxMapper;
        this.objectMapper = om;
        this.rocketMQTemplate = rt;
    }

    @Override
    protected void postLuaSuccess(LocalDate date, String trainCode, String startStation, String endStation,
                                   int seatTypeCode, int carriageNum, int seatNum, int startSection, int endSection,
                                   int totalSectionCount, int passengerCount, String sectionsJson, long seatStartBit,
                                   List<TicketBuyDTO.Passenger> passengerList) {
        try {
            Map<String, Object> orderPayload = new HashMap<>();
            orderPayload.put("userId", BaseContext.getCurrentId() != null ? BaseContext.getCurrentId() : 2050050560936701953L);
            orderPayload.put("date", date.toString());
            orderPayload.put("trainCode", trainCode);
            orderPayload.put("startStation", startStation);
            orderPayload.put("endStation", endStation);
            orderPayload.put("seatType", seatTypeCode);
            orderPayload.put("carriageNum", carriageNum);
            orderPayload.put("seatNum", seatNum);
            orderPayload.put("startSection", startSection);
            orderPayload.put("endSection", endSection);
            orderPayload.put("totalSectionCount", totalSectionCount);
            orderPayload.put("passengerCount", passengerCount);
            orderPayload.put("sectionsJson", sectionsJson);
            orderPayload.put("seatStartBit", seatStartBit);
            List<Map<String, String>> passengers = passengerList.stream()
                    .map(p -> { Map<String, String> m = new HashMap<>(); m.put("realName",p.getRealName()); m.put("idCard",p.getIdCard()); return m; })
                    .collect(Collectors.toList());
            orderPayload.put("passengers", passengers);
            String payloadJson = objectMapper.writeValueAsString(orderPayload);

            // Local message table
            TicketOutbox outbox = TicketOutbox.builder()
                    .messageType("ORDER_CREATE").payload(payloadJson).status("PENDING")
                    .retryCount(0).createTime(LocalDateTime.now()).nextRetry(LocalDateTime.now()).build();
            outboxMapper.insert(outbox);

            // Delayed close via MQ (Canal handles ORDER_CREATE, delayed close is separate)
        } catch (Exception e) {
            log.error("Outbox写入失败", e);
        }
    }
}
