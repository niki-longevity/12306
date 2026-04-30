package com.project.ticket.handler.chain;

import com.project.ticket.utils.TicketValidateContext;
import com.project.ticket.pojo.dto.TicketBuyDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

/**
 * 处理器1：购票参数非空校验
 */
@Slf4j
public class ParamValidateHandler extends AbstractTicketValidateHandler {

    @Override
    public void handle(TicketValidateContext context) {
        // 1. 先校验当前处理器的逻辑
        TicketBuyDTO dto = context.getTicketBuyDTO();
        if (dto == null) {
            context.setPass(false);
            context.setErrorMsg("购票请求DTO为空");
            log.error("购票参数校验失败：DTO为空");
            return;
        }

        // 提取参数并校验
        context.setDate(dto.getDate());
        context.setTrainCode(dto.getCode());
        context.setStartStation(dto.getStartStation());
        context.setEndStation(dto.getEndStation());
        context.setSeatType(dto.getSeatType());
        context.setPassengerCount(CollectionUtils.isEmpty(dto.getPassengerList()) ? 0 : dto.getPassengerList().size());

        if (context.getDate() == null || context.getTrainCode() == null 
                || context.getStartStation() == null || context.getEndStation() == null
                || context.getPassengerCount() <= 0) {
            context.setPass(false);
            context.setErrorMsg("参数错误：日期/车次/起止站/乘车人不能为空");
            log.error("购票参数错误：{}", dto);
            return;
        }

        // 2. 校验通过，传递给下一个处理器
        if (nextHandler != null) {
            nextHandler.handle(context);
        }
    }
}