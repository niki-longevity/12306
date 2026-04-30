package com.project.controller;

import com.project.pojo.entity.Order;
import com.project.result.Result;
import com.project.service.OrderService;
import com.project.utils.BaseContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/list")
    public Result<List<Order>> list() {
        Long userId = BaseContext.getCurrentId();
        List<Order> orders = orderService.findByUser(userId);
        return Result.success(orders);
    }

    @GetMapping("/{id}")
    public Result<Order> getById(@PathVariable Long id) {
        Order order = orderService.findById(id);
        return Result.success(order);
    }

    @PutMapping("/{id}/pay")
    public Result<Order> pay(@PathVariable Long id) {
        Long userId = BaseContext.getCurrentId();
        Order order = orderService.pay(id, userId);
        return Result.success(order);
    }

    @PutMapping("/{id}/cancel")
    public Result<Order> cancel(@PathVariable Long id) {
        Long userId = BaseContext.getCurrentId();
        Order order = orderService.cancel(id, userId);
        return Result.success(order);
    }
}
