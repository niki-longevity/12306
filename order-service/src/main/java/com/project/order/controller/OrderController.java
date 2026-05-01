package com.project.order.controller;

import com.project.common.pojo.entity.Order;
import com.project.common.pojo.entity.OrderPassenger;
import com.project.common.result.Result;
import com.project.order.service.OrderService;
import com.project.common.utils.BaseContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    @PostMapping("/create")
    public Result<Order> create(@RequestBody Map<String, Object> request) {
        Order order = Order.builder()
                .userId(Long.valueOf(request.get("userId").toString()))
                .date(LocalDate.parse(request.get("date").toString()))
                .trainCode(request.get("trainCode").toString())
                .startStation(request.get("startStation").toString())
                .endStation(request.get("endStation").toString())
                .seatType(Integer.valueOf(request.get("seatType").toString()))
                .carriageNum(Integer.valueOf(request.get("carriageNum").toString()))
                .seatNum(Integer.valueOf(request.get("seatNum").toString()))
                .startSection(Integer.valueOf(request.get("startSection").toString()))
                .endSection(Integer.valueOf(request.get("endSection").toString()))
                .totalSectionCount(Integer.valueOf(request.get("totalSectionCount").toString()))
                .passengerCount(Integer.valueOf(request.get("passengerCount").toString()))
                .sectionsJson(request.get("sectionsJson").toString())
                .seatStartBit(Long.valueOf(request.get("seatStartBit").toString()))
                .build();

        @SuppressWarnings("unchecked")
        Object pObj = request.get("passengers");
        List<Map<String, Object>> passengerMaps;
        if (pObj instanceof List) {
            passengerMaps = (List<Map<String, Object>>) pObj;
        } else if (pObj instanceof Map) {
            passengerMaps = List.of((Map<String, Object>) pObj);
        } else {
            throw new RuntimeException("Invalid passengers format: " + pObj);
        }
        List<OrderPassenger> passengers = passengerMaps.stream()
                .map(m -> OrderPassenger.builder()
                        .realName((String) m.get("realName")).idCard((String) m.get("idCard")).build())
                .collect(Collectors.toList());

        return Result.success(orderService.create(order, passengers));
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
