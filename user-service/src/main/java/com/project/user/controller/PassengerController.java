package com.project.user.controller;

import com.project.common.pojo.dto.PassengerSaveDTO;
import com.project.common.pojo.entity.Passenger;
import com.project.common.result.Result;
import com.project.common.utils.BaseContext;
import com.project.user.service.PassengerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/user/passenger")
@RequiredArgsConstructor
public class PassengerController {

    private final PassengerService passengerService;

    @GetMapping("/list")
    public Result<List<Passenger>> list() {
        Long userId = BaseContext.getCurrentId();
        List<Passenger> list = passengerService.list(userId);
        return Result.success(list);
    }

    @PostMapping
    public Result<Passenger> add(@RequestBody PassengerSaveDTO dto) {
        Long userId = BaseContext.getCurrentId();
        Passenger passenger = passengerService.add(userId, dto);
        return Result.success(passenger);
    }

    @PutMapping("/{id}")
    public Result<Passenger> update(@PathVariable Long id, @RequestBody PassengerSaveDTO dto) {
        Long userId = BaseContext.getCurrentId();
        Passenger passenger = passengerService.update(id, userId, dto);
        return Result.success(passenger);
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        Long userId = BaseContext.getCurrentId();
        passengerService.delete(id, userId);
        return Result.success("删除成功");
    }
}
