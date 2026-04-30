package com.project.mq;

import com.project.pojo.entity.Order;
import com.project.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "order-close-topic",
        consumerGroup = "order-close-consumer-group"
)
public class OrderCloseConsumer implements RocketMQListener<String> {

    private final OrderService orderService;

    @Override
    public void onMessage(String message) {
        Long orderId = Long.valueOf(message);
        log.info("收到关单检查消息：orderId={}", orderId);

        try {
            Order order = orderService.closeExpiredOrder(orderId);
            if (order != null) {
                log.info("延时关单成功：orderId={}", orderId);
                // Redis rollback is handled by the fallback scheduler (Task 7)
            }
        } catch (Exception e) {
            log.error("关单处理失败：orderId={}", orderId, e);
        }
    }
}
