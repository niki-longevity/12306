package com.project.ticket.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.utils.BaseContext;
import com.project.ticket.pojo.dto.TicketBuyDTO;
import com.project.ticket.handler.builder.TicketValidateChainBuilder;
import com.project.ticket.service.impl.TicketBuyServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * HTTP variant: sync call order-service /order/create.
 * Active only with profile "bench-http".
 */
@Slf4j
@Service
@Profile("bench-http")
public class HttpImpl extends TicketBuyServiceImpl {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HttpImpl(StringRedisTemplate str, RedissonClient rc, CacheManager cm, ObjectMapper om,
                    TicketValidateChainBuilder cb, RestTemplate restTemplate) {
        super(str, rc, cm, om, cb, null); // rocketMQTemplate is null for this impl
        this.restTemplate = restTemplate;
        this.objectMapper = om;
    }

    @Override
    protected void postLuaSuccess(LocalDate date, String trainCode, String startStation, String endStation,
                                   int seatTypeCode, int carriageNum, int seatNum, int startSection, int endSection,
                                   int totalSectionCount, int passengerCount, String sectionsJson, long seatStartBit,
                                   List<TicketBuyDTO.Passenger> passengerList) {
        try {
            Map<String, Object> orderRequest = new HashMap<>();
            orderRequest.put("userId", BaseContext.getCurrentId());
            orderRequest.put("date", date.toString());
            orderRequest.put("trainCode", trainCode);
            orderRequest.put("startStation", startStation);
            orderRequest.put("endStation", endStation);
            orderRequest.put("seatType", seatTypeCode);
            orderRequest.put("carriageNum", carriageNum);
            orderRequest.put("seatNum", seatNum);
            orderRequest.put("startSection", startSection);
            orderRequest.put("endSection", endSection);
            orderRequest.put("totalSectionCount", totalSectionCount);
            orderRequest.put("passengerCount", passengerCount);
            orderRequest.put("sectionsJson", sectionsJson);
            orderRequest.put("seatStartBit", seatStartBit);

            List<Map<String, String>> passengers = passengerList.stream()
                    .map(p -> { Map<String, String> m = new HashMap<>(); m.put("realName",p.getRealName()); m.put("idCard",p.getIdCard()); return m; })
                    .collect(Collectors.toList());
            orderRequest.put("passengers", passengers);

            restTemplate.postForObject("http://localhost:8083/order/create", orderRequest, String.class);
        } catch (Exception e) {
            log.error("HTTP创建订单失败：车次{}", trainCode, e);
        }
    }
}
