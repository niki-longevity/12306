package com.project.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.project.order.mapper.OrderMapper;
import com.project.order.mapper.OrderPassengerMapper;
import com.project.order.mapper.SeatBitmapMapper;
import com.project.common.pojo.entity.Order;
import com.project.common.pojo.entity.OrderPassenger;
import com.project.common.pojo.entity.SeatBitmap;
import com.project.common.exception.BaseException;
import com.project.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderPassengerMapper orderPassengerMapper;
    private final SeatBitmapMapper seatBitmapMapper;
    private final StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> CONFLICT_FIX_LUA;
    private static final DefaultRedisScript<Long> REFUND_LUA;
    static {
        CONFLICT_FIX_LUA = new DefaultRedisScript<>();
        CONFLICT_FIX_LUA.setLocation(new org.springframework.core.io.ClassPathResource("lua/ticket_conflict_fix.lua"));
        CONFLICT_FIX_LUA.setResultType(Long.class);

        REFUND_LUA = new DefaultRedisScript<>();
        REFUND_LUA.setLocation(new org.springframework.core.io.ClassPathResource("lua/ticket_refund.lua"));
        REFUND_LUA.setResultType(Long.class);
    }

    @Override
    @Transactional
    public Order create(Order order, List<OrderPassenger> passengers) {
        byte[] mask = buildSectionMask(order.getSeatStartBit(),
                order.getStartSection(), order.getEndSection(), order.getTotalSectionCount());

        long snowflakeId = order.getDate().toEpochDay() * 1000000L + order.getCarriageNum() * 1000L + order.getSeatNum();
        int updated = seatBitmapMapper.upsertBitmap(snowflakeId,
                order.getTrainCode(), order.getDate(), order.getSeatType(),
                order.getCarriageNum(), order.getSeatNum(), mask);

        if (updated == 0) {
            log.warn("Bitmap conflict: train={}, seat={}/{} sect={}-{}",
                    order.getTrainCode(), order.getCarriageNum(), order.getSeatNum(),
                    order.getStartSection(), order.getEndSection());

            SeatBitmap current = seatBitmapMapper.selectOne(
                    new LambdaQueryWrapper<SeatBitmap>()
                            .eq(SeatBitmap::getTrainCode, order.getTrainCode())
                            .eq(SeatBitmap::getDate, order.getDate())
                            .eq(SeatBitmap::getSeatType, order.getSeatType())
                            .eq(SeatBitmap::getCarriageNum, order.getCarriageNum())
                            .eq(SeatBitmap::getSeatNum, order.getSeatNum())
                            .last("LIMIT 1"));

            if (current != null && current.getBitmap() != null) {
                repairRedisAfterConflict(order, current.getBitmap(), mask);
            }
            throw new BaseException("座位冲突，请重试");
        }

        order.setStatus("UNPAID");
        order.setExpireTime(LocalDateTime.now().plusMinutes(30));
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.insert(order);

        passengers.forEach(p -> {
            p.setOrderId(order.getId());
            orderPassengerMapper.insert(p);
        });
        log.info("订单创建成功：orderId={}, trainCode={}", order.getId(), order.getTrainCode());
        return order;
    }

    @Override
    public Order pay(Long orderId, Long userId) {
        LambdaUpdateWrapper<Order> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Order::getId, orderId)
               .eq(Order::getUserId, userId)
               .eq(Order::getStatus, "UNPAID")
               .set(Order::getStatus, "PAID")
               .set(Order::getUpdateTime, LocalDateTime.now());
        int rows = orderMapper.update(null, wrapper);
        if (rows == 0) {
            throw new BaseException("支付失败：订单不存在或已过期");
        }

        // 模拟第三方支付网关延迟
        try { Thread.sleep(800 + (long)(Math.random() * 800)); } catch (InterruptedException ignored) {}

        Order order = orderMapper.selectById(orderId);
        // 生成模拟交易号
        String paymentNo = "PAY" + System.currentTimeMillis() + String.format("%04d", (int)(Math.random() * 10000));
        order.setPaymentNo(paymentNo);
        log.info("订单支付成功：orderId={}, paymentNo={}", orderId, paymentNo);
        return order;
    }

    @Override
    @Transactional
    public Order cancel(Long orderId, Long userId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BaseException("取消失败：订单不存在或无法取消");
        }
        String oldStatus = order.getStatus();
        if (!"UNPAID".equals(oldStatus) && !"PAID".equals(oldStatus)) {
            throw new BaseException("取消失败：订单状态不允许取消");
        }

        LambdaUpdateWrapper<Order> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Order::getId, orderId)
               .eq(Order::getUserId, userId)
               .in(Order::getStatus, "UNPAID", "PAID")
               .set(Order::getStatus, "CANCELLED")
               .set(Order::getUpdateTime, LocalDateTime.now());
        int rows = orderMapper.update(null, wrapper);
        if (rows == 0) {
            throw new BaseException("取消失败：订单不存在或无法取消");
        }

        // 任何取消都需要回滚Redis和MySQL座位（购票时已扣减，取消必须归还）
        rollbackRedisSeat(order);
        byte[] clearMask = buildCancelMask(order.getSeatStartBit(),
                order.getStartSection(), order.getEndSection(), order.getTotalSectionCount());
        seatBitmapMapper.clearBitmap(
                order.getTrainCode(), order.getDate(), order.getSeatType(),
                order.getCarriageNum(), order.getSeatNum(), clearMask);

        log.info("订单手动取消：orderId={}, 原状态={}", orderId, oldStatus);
        return orderMapper.selectById(orderId);
    }

    private void rollbackRedisSeat(Order order) {
        String bitmapKey = String.format("%s:%s:%d:bitmap",
                order.getDate(), order.getTrainCode(), order.getSeatType());
        String stockKey = String.format("Stock:%s:%s:%d",
                order.getDate(), order.getTrainCode(), order.getSeatType());

        stringRedisTemplate.execute(REFUND_LUA,
                Arrays.asList(bitmapKey, stockKey),
                String.valueOf(order.getSeatStartBit()),
                String.valueOf(order.getStartSection()),
                String.valueOf(order.getEndSection()),
                String.valueOf(order.getTotalSectionCount()),
                String.valueOf(order.getPassengerCount()),
                order.getSectionsJson() != null ? order.getSectionsJson() : "[]");
        log.info("Redis座位回滚成功：orderId={}, bitmapKey={}", order.getId(), bitmapKey);
    }

    private byte[] buildCancelMask(long seatStartBit, int startSection, int endSection, int totalSectionCount) {
        int byteLen = (totalSectionCount + 7) / 8;
        byte[] mask = new byte[byteLen];
        for (int s = startSection; s <= endSection; s++) {
            int bitPos = s - 1;
            int byteIdx = bitPos / 8;
            int bitIdx = bitPos % 8;
            mask[byteIdx] |= (1 << bitIdx);
        }
        return mask;
    }

    @Override
    public Order closeExpiredOrder(Long orderId) {
        LambdaUpdateWrapper<Order> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Order::getId, orderId)
               .eq(Order::getStatus, "UNPAID")
               .set(Order::getStatus, "CANCELLED")
               .set(Order::getUpdateTime, LocalDateTime.now());
        int rows = orderMapper.update(null, wrapper);
        if (rows == 0) {
            log.debug("关单跳过（已支付或已取消）：orderId={}", orderId);
            return null;
        }
        log.info("超时关单成功：orderId={}", orderId);
        return orderMapper.selectById(orderId);
    }

    @Override
    public List<Order> findByUser(Long userId) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getUserId, userId)
               .orderByDesc(Order::getCreateTime);
        return orderMapper.selectList(wrapper);
    }

    @Override
    public Order findById(Long orderId) {
        return orderMapper.selectById(orderId);
    }

    /**
     * 构建区间位图掩码。mask 的 bit (section-1) 位置为 1。
     */
    private byte[] buildSectionMask(long seatStartBit, int startSection, int endSection, int totalSectionCount) {
        int byteLen = (totalSectionCount + 7) / 8;
        byte[] mask = new byte[byteLen];
        for (int s = startSection; s <= endSection; s++) {
            int bitPos = s - 1;  // 座位内相对偏移，s从1开始
            int byteIdx = bitPos / 8;
            int bitIdx = bitPos % 8;
            mask[byteIdx] |= (1 << bitIdx);
        }
        return mask;
    }

    /**
     * Redis 冲突修复：清零干净区间 + 标记脏区间 + 修正库存和令牌
     */
    private void repairRedisAfterConflict(Order order, byte[] mysqlBitmap, byte[] userMask) {
        long seatStartBit = order.getSeatStartBit();
        int userStart = order.getStartSection();
        int userEnd = order.getEndSection();
        int totalSectionCount = order.getTotalSectionCount();

        List<Integer> cleanSections = new ArrayList<>();
        List<Integer> dirtySections = new ArrayList<>();

        for (int s = userStart; s <= userEnd; s++) {
            int bitPos = s - 1;  // 座位内相对偏移
            int byteIdx = bitPos / 8;
            int bitIdx = bitPos % 8;

            boolean userWants = byteIdx < userMask.length && (userMask[byteIdx] & (1 << bitIdx)) != 0;
            boolean mysqlHas  = byteIdx < mysqlBitmap.length && (mysqlBitmap[byteIdx] & (1 << bitIdx)) != 0;

            if (userWants && mysqlHas) {
                dirtySections.add(s);   // Redis 丢失了这段已售数据
            } else if (userWants && !mysqlHas) {
                cleanSections.add(s);    // 用户想买但需要回滚的
            }
        }

        log.warn("Conflict repair: cleanSections={}, dirtySections={}", cleanSections, dirtySections);

        String bitmapKey = String.format("%s:%s:%d:bitmap", order.getDate(), order.getTrainCode(), order.getSeatType());
        String stockKey = String.format("Stock:%s:%s:%d", order.getDate(), order.getTrainCode(), order.getSeatType());
        String tokenKey = String.format("Token:%s:%s:%d", order.getDate(), order.getTrainCode(), order.getSeatType());

        int tokenDelta = cleanSections.size() - dirtySections.size();

        stringRedisTemplate.execute(CONFLICT_FIX_LUA,
                Arrays.asList(bitmapKey, stockKey, tokenKey),
                String.valueOf(seatStartBit),
                String.valueOf(totalSectionCount),
                cleanSections.toString(),
                dirtySections.toString(),
                String.valueOf(order.getPassengerCount()),
                cleanSections.toString()
        );
    }
}
