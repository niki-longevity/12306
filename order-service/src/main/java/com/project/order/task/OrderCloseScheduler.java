package com.project.order.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.project.order.mapper.OrderMapper;
import com.project.common.pojo.entity.Order;
import com.project.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class OrderCloseScheduler {

    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> REFUND_LUA_SCRIPT;
    static {
        REFUND_LUA_SCRIPT = new DefaultRedisScript<>();
        REFUND_LUA_SCRIPT.setLocation(
                new org.springframework.core.io.ClassPathResource("lua/ticket_refund.lua"));
        REFUND_LUA_SCRIPT.setResultType(Long.class);
    }

    /**
     * 每10分钟扫描一次过期未支付订单，兜底延时消息可能的丢失
     */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void closeExpiredOrders() {
        log.debug("定时任务：开始扫描过期未支付订单...");

        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getStatus, "UNPAID")
               .lt(Order::getExpireTime, java.time.LocalDateTime.now())
               .last("LIMIT 100");
        List<Order> expiredOrders = orderMapper.selectList(wrapper);

        if (expiredOrders.isEmpty()) {
            log.debug("无过期未支付订单");
            return;
        }

        log.info("定时任务：发现{}条过期未支付订单，开始关单...", expiredOrders.size());
        for (Order order : expiredOrders) {
            try {
                Order closed = orderService.closeExpiredOrder(order.getId());
                if (closed != null) {
                    rollbackRedisSeat(order);
                    log.info("定时任务关单+回滚Redis成功：orderId={}", order.getId());
                }
            } catch (Exception e) {
                log.error("定时任务关单失败：orderId={}", order.getId(), e);
            }
        }
    }

    /**
     * 回滚Redis座位（位图清零 + 库存加回）
     */
    private void rollbackRedisSeat(Order order) {
        String bitmapKey = String.format("%s:%s:%d:bitmap",
                order.getDate(), order.getTrainCode(), order.getSeatType());
        String stockKey = String.format("Stock:%s:%s:%d",
                order.getDate(), order.getTrainCode(), order.getSeatType());

        stringRedisTemplate.execute(
                REFUND_LUA_SCRIPT,
                Arrays.asList(bitmapKey, stockKey),
                String.valueOf(order.getSeatStartBit()),
                String.valueOf(order.getStartSection()),
                String.valueOf(order.getEndSection()),
                String.valueOf(order.getTotalSectionCount()),
                String.valueOf(order.getPassengerCount()),
                order.getSectionsJson()
        );
        log.info("Redis座位回滚成功：orderId={}, bitmapKey={}", order.getId(), bitmapKey);
    }
}
