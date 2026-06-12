package com.project.ticket.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.project.common.result.Result;
import com.project.ticket.mapper.StationDictMapper;
import com.project.ticket.mapper.TrainTemplateMapper;
import com.project.ticket.mapper.TrainTemplateStopoverMapper;
import com.project.ticket.pojo.entity.StationDict;
import com.project.ticket.pojo.entity.TrainTemplate;
import com.project.ticket.pojo.entity.TrainTemplateStopover;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@CrossOrigin(origins = "*")
@RestController
@Slf4j
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final TrainTemplateMapper templateMapper;
    private final TrainTemplateStopoverMapper stopoverMapper;
    private final StationDictMapper stationMapper;

    @GetMapping("/trains")
    public Result<List<TrainTemplate>> listTrains() {
        return Result.success(templateMapper.selectList(new LambdaQueryWrapper<>()));
    }

    @GetMapping("/trains/{code}/stops")
    public Result<List<TrainTemplateStopover>> trainStops(@PathVariable String code) {
        List<TrainTemplateStopover> stops = stopoverMapper.selectList(
                new LambdaQueryWrapper<TrainTemplateStopover>()
                        .eq(TrainTemplateStopover::getTrainCode, code)
                        .orderByAsc(TrainTemplateStopover::getStationIndex));
        return Result.success(stops);
    }

    @GetMapping("/stations")
    public Result<List<StationDict>> listStations() {
        return Result.success(stationMapper.selectList(
                new LambdaQueryWrapper<StationDict>().orderByAsc(StationDict::getSortOrder)));
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        long trainCount = templateMapper.selectCount(new LambdaQueryWrapper<>());
        long stationCount = stationMapper.selectCount(new LambdaQueryWrapper<>());
        long stopoverCount = stopoverMapper.selectCount(new LambdaQueryWrapper<>());
        return Result.success(Map.of(
                "trainCount", trainCount,
                "stationCount", stationCount,
                "stopoverCount", stopoverCount));
    }
}
