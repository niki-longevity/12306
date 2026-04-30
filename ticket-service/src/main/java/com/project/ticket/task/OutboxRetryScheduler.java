package com.project.ticket.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.project.ticket.mapper.TicketOutboxMapper;
import com.project.ticket.pojo.entity.TicketOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rocketmq.name-server")
public class OutboxRetryScheduler {

    private final TicketOutboxMapper outboxMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private static final int MAX_RETRIES = 5;

    @Scheduled(fixedRate = 5000)
    public void retryPendingMessages() {
        LambdaQueryWrapper<TicketOutbox> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TicketOutbox::getStatus, "PENDING")
               .lt(TicketOutbox::getNextRetry, LocalDateTime.now())
               .last("LIMIT 100");
        List<TicketOutbox> pendingList = outboxMapper.selectList(wrapper);
        if (pendingList.isEmpty()) return;

        for (TicketOutbox outbox : pendingList) {
            try {
                String topic = "ORDER_CREATE".equals(outbox.getMessageType())
                        ? "order-create-topic" : "order-close-topic";
                rocketMQTemplate.syncSend(topic,
                        MessageBuilder.withPayload(outbox.getPayload()).build(),
                        3000, 0);
                outbox.setStatus("SENT");
                outboxMapper.updateById(outbox);
                log.debug("Outbox sent: id={}", outbox.getId());
            } catch (Exception e) {
                int retries = outbox.getRetryCount() + 1;
                outbox.setRetryCount(retries);
                outbox.setNextRetry(LocalDateTime.now().plusSeconds(10L * retries));
                if (retries >= MAX_RETRIES) {
                    outbox.setStatus("DEAD");
                    log.error("Outbox DEAD after {} retries: id={}", retries, outbox.getId());
                }
                outboxMapper.updateById(outbox);
            }
        }
    }
}
