package com.project.ticket.handler.chain;

import com.project.ticket.utils.TicketValidateContext;
import com.project.ticket.pojo.bo.TicketListBO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 处理器2：车次信息校验（存在性、日期、是否出发）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainInfoValidateHandler extends AbstractTicketValidateHandler {
    private final CacheManager trainStopCacheManager;
    private static final String CACHE_NAME = "trainStopCache";

    @Override
    public void handle(TicketValidateContext context) {
        // 1. 前置校验：如果上一步失败，直接返回
        if (!context.isPass()) {
            return;
        }

        LocalDate date = context.getDate();
        String trainCode = context.getTrainCode();
        // 构建缓存Key（复用预热类的逻辑）
        String cacheKey = date.toString() + ":" + trainCode;

        // 2. 校验车次是否存在（从缓存获取）
        Cache cache = trainStopCacheManager.getCache(CACHE_NAME);
        if (cache == null) {
            context.setPass(false);
            context.setErrorMsg("缓存实例获取失败，无法校验车次信息");
            log.error("获取缓存{}失败", CACHE_NAME);
            return;
        }
        TicketListBO ticketListBO = cache.get(cacheKey, TicketListBO.class);
        if (ticketListBO == null) {
            context.setPass(false);
            context.setErrorMsg("车次不存在或未开放购票：" + trainCode + "(" + date + ")");
            log.error("车次{}({})不存在于缓存", trainCode, date);
            return;
        }
        context.setTicketListBO(ticketListBO); // 存入上下文供后续处理器使用

        // 3. 校验日期是否在发售期（示例逻辑，可替换为真实发售规则）
        LocalDate today = LocalDate.now();
        if (date.isBefore(today)) {
            context.setPass(false);
            context.setErrorMsg("购票日期不能早于当前日期：" + date);
            log.error("车次{}购票日期{}早于当前日期", trainCode, date);
            return;
        }

        // 4. 校验车次是否已出发（取始发站发车时间对比）
        LocalTime startTime = ticketListBO.getStopoverStations().get(0).getOutTime();
        if (startTime != null && LocalTime.now().isAfter(startTime) && date.isEqual(today)) {
            context.setPass(false);
            context.setErrorMsg("车次已出发，无法购票：" + trainCode);
            log.error("车次{}({})已出发（发车时间：{}）", trainCode, date, startTime);
            return;
        }

        // 5. 校验通过，传递给下一个处理器
        if (nextHandler != null) {
            nextHandler.handle(context);
        }
    }
}