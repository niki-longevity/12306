package com.project.ticket.handler.builder;

import com.project.ticket.handler.chain.AbstractTicketValidateHandler;
import com.project.ticket.handler.chain.ParamValidateHandler;
import com.project.ticket.handler.chain.StationValidateHandler;
import com.project.ticket.handler.chain.TrainInfoValidateHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 购票校验责任链构建器：封装链条组装逻辑，对外提供统一入口
 */
@Component
@RequiredArgsConstructor
public class TicketValidateChainBuilder {
    private final TrainInfoValidateHandler trainInfoValidateHandler;
    private final StationValidateHandler stationValidateHandler;

    /**
     * 构建完整的校验链条
     */
    public AbstractTicketValidateHandler buildChain() {
        // 1. 创建参数校验处理器（无依赖，手动new）
        ParamValidateHandler paramHandler = new ParamValidateHandler();
        // 2. 组装链条：参数 → 车次信息 → 车站
        paramHandler.setNextHandler(trainInfoValidateHandler)
                   .setNextHandler(stationValidateHandler);
        return paramHandler; // 返回链条第一个处理器
    }
}