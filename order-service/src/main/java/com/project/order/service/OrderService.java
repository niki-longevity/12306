package com.project.order.service;

import com.project.common.pojo.entity.Order;
import com.project.common.pojo.entity.OrderPassenger;

import java.util.List;

public interface OrderService {
    Order create(Order order, List<OrderPassenger> passengers);
    Order pay(Long orderId, Long userId);
    Order cancel(Long orderId, Long userId);
    Order closeExpiredOrder(Long orderId);
    List<Order> findByUser(Long userId);
    Order findById(Long orderId);
}
