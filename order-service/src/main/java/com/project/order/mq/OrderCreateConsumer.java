package com.project.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.pojo.entity.Order;
import com.project.common.pojo.entity.OrderPassenger;
import com.project.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(topic = "order-create-topic", consumerGroup = "order-create-consumer-group")
public class OrderCreateConsumer implements RocketMQListener<String> {

    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    @Override
    @SuppressWarnings("unchecked")
    public void onMessage(String message) {
        try {
            Map<String, Object> payload = objectMapper.readValue(message, Map.class);
            Order order = Order.builder()
                    .userId(Long.valueOf(payload.get("userId").toString()))
                    .date(LocalDate.parse(payload.get("date").toString()))
                    .trainCode(payload.get("trainCode").toString())
                    .startStation(payload.get("startStation").toString())
                    .endStation(payload.get("endStation").toString())
                    .seatType(Integer.valueOf(payload.get("seatType").toString()))
                    .carriageNum(Integer.valueOf(payload.get("carriageNum").toString()))
                    .seatNum(Integer.valueOf(payload.get("seatNum").toString()))
                    .startSection(Integer.valueOf(payload.get("startSection").toString()))
                    .endSection(Integer.valueOf(payload.get("endSection").toString()))
                    .totalSectionCount(Integer.valueOf(payload.get("totalSectionCount").toString()))
                    .passengerCount(Integer.valueOf(payload.get("passengerCount").toString()))
                    .sectionsJson(payload.get("sectionsJson").toString())
                    .seatStartBit(Long.valueOf(payload.get("seatStartBit").toString()))
                    .build();

            List<Map<String, String>> passengerMaps = (List<Map<String, String>>) payload.get("passengers");
            List<OrderPassenger> passengers = passengerMaps.stream()
                    .map(m -> OrderPassenger.builder()
                            .realName(m.get("realName")).idCard(m.get("idCard")).build())
                    .toList();

            orderService.create(order, passengers);
            log.info("OrderCreateConsumer: 订单创建成功, {}", order.getTrainCode());
        } catch (Exception e) {
            log.error("OrderCreateConsumer: 处理失败", e);
            throw new RuntimeException(e);
        }
    }
}
