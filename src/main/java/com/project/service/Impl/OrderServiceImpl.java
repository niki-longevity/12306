package com.project.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.project.mapper.OrderMapper;
import com.project.mapper.OrderPassengerMapper;
import com.project.pojo.entity.Order;
import com.project.pojo.entity.OrderPassenger;
import com.project.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderPassengerMapper orderPassengerMapper;

    @Override
    @Transactional
    public Order create(Order order, List<OrderPassenger> passengers) {
        order.setStatus("UNPAID");
        order.setExpireTime(LocalDateTime.now().plusMinutes(30));
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.insert(order);

        passengers.forEach(p -> {
            p.setOrderId(order.getId());
            orderPassengerMapper.insert(p);
        });

        log.info("订单创建成功：orderId={}, trainCode={}, expireTime={}",
                order.getId(), order.getTrainCode(), order.getExpireTime());
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
            throw new RuntimeException("支付失败：订单不存在或已过期");
        }
        log.info("订单支付成功：orderId={}", orderId);
        return orderMapper.selectById(orderId);
    }

    @Override
    public Order cancel(Long orderId, Long userId) {
        LambdaUpdateWrapper<Order> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Order::getId, orderId)
               .eq(Order::getUserId, userId)
               .eq(Order::getStatus, "UNPAID")
               .set(Order::getStatus, "CANCELLED")
               .set(Order::getUpdateTime, LocalDateTime.now());
        int rows = orderMapper.update(null, wrapper);
        if (rows == 0) {
            throw new RuntimeException("取消失败：订单不存在或无法取消");
        }
        log.info("订单手动取消：orderId={}", orderId);
        return orderMapper.selectById(orderId);
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
}
