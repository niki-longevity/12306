package com.project.ticket.handler.chain;

import com.project.ticket.utils.TicketValidateContext;
import com.project.ticket.pojo.bo.TicketListBO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 处理器3：车站校验（存在性、顺序）
 */
@Slf4j
@Component
public class StationValidateHandler extends AbstractTicketValidateHandler {

    @Override
    public void handle(TicketValidateContext context) {
        // 1. 前置校验：如果上一步失败，直接返回
        if (!context.isPass()) {
            return;
        }

        TicketListBO bo = context.getTicketListBO();
        String startStation = context.getStartStation();
        String endStation = context.getEndStation();

        // 2. 获取车次所有经停站
        List<TicketListBO.StopoverStation> stopoverStations = bo.getStopoverStations();
        if (stopoverStations.isEmpty()) {
            context.setPass(false);
            context.setErrorMsg("车次无经停站信息：" + bo.getCode());
            log.error("车次{}无经停站信息", bo.getCode());
            return;
        }

        // 3. 校验起始站是否存在
        Optional<TicketListBO.StopoverStation> startOpt = stopoverStations.stream()
                .filter(s -> s.getStopoverStation().equals(startStation))
                .findFirst();
        if (startOpt.isEmpty()) {
            context.setPass(false);
            context.setErrorMsg("起始站不在车次经停范围内：" + startStation);
            log.error("车次{}无起始站{}", bo.getCode(), startStation);
            return;
        }

        // 4. 校验终点站是否存在
        Optional<TicketListBO.StopoverStation> endOpt = stopoverStations.stream()
                .filter(s -> s.getStopoverStation().equals(endStation))
                .findFirst();
        if (endOpt.isEmpty()) {
            context.setPass(false);
            context.setErrorMsg("终点站不在车次经停范围内：" + endStation);
            log.error("车次{}无终点站{}", bo.getCode(), endStation);
            return;
        }

        // 5. 校验车站顺序（起始站索引 < 终点站索引）
        int startIndex = startOpt.get().getStationIndex();
        int endIndex = endOpt.get().getStationIndex();
        if (startIndex >= endIndex) {
            context.setPass(false);
            context.setErrorMsg("起始站顺序不能晚于终点站：" + startStation + "→" + endStation);
            log.error("车次{}起始站{}索引({}) ≥ 终点站{}索引({})", bo.getCode(), startStation, startIndex, endStation, endIndex);
            return;
        }

        // 6. 校验通过，已经没有下一个处理器，不用传递
    }
}