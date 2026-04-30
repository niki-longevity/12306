package com.project.ticket.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.pojo.entity.Order;
import com.project.common.pojo.entity.OrderPassenger;
import com.project.common.result.Result;
import com.project.common.utils.BaseContext;
import com.project.ticket.handler.builder.TicketValidateChainBuilder;
import com.project.ticket.handler.chain.AbstractTicketValidateHandler;
import com.project.ticket.utils.TicketValidateContext;
import com.project.ticket.mapper.OrderMapper;
import com.project.ticket.mapper.OrderPassengerMapper;
import com.project.ticket.pojo.bo.TicketListBO;
import com.project.ticket.pojo.dto.TicketBuyDTO;
import com.project.ticket.pojo.enums.SeatType;
import com.project.ticket.service.TicketBuyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketBuyServiceImpl implements TicketBuyService {

    // ===== 基础配置 =====
    private static final String TOKEN_KEY_PREFIX = "Token:%s:%s";
    private static final String STOCK_KEY_PREFIX = "Stock:%s:%s:%d";
    private static final String BITMAP_KEY_PREFIX = "%s:%s:%d:bitmap";
    private static final String LOCAL_LOCK_KEY_PREFIX = "LocalLock:%s:%s:%d:%d:%d";

    // V1: 熔断参数
    private static final int MAX_ATTEMPTS = 100;
    private static final long TIMEOUT_MS = 5000;

    // ===== 座位全局顺序编号定义 =====
    private static final List<Integer> BUSINESS_SEAT_GLOBAL_INDEX = Arrays.asList(1, 2, 3, 4, 5);
    private static final List<Integer> FIRST_SEAT_GLOBAL_INDEX = initFirstSeatGlobalIndex();
    private static final List<Integer> SECOND_SEAT_GLOBAL_INDEX = initSecondSeatGlobalIndex();

    // ===== 依赖注入 =====
    private final StringRedisTemplate stringRedisTemplate;
    private final CacheManager trainStopCacheManager;
    private final ObjectMapper objectMapper;
    private final TicketValidateChainBuilder ticketValidateChainBuilder;
    private final OrderMapper orderMapper;
    private final OrderPassengerMapper orderPassengerMapper;

    // 本地锁
    private final ConcurrentHashMap<String, ReentrantLock> localLockMap = new ConcurrentHashMap<>();

    // Lua 脚本（从文件加载）
    private static final DefaultRedisScript<Long> TICKET_BUY_LUA_SCRIPT;
    private static final DefaultRedisScript<Long> TOKEN_BUCKET_LUA_SCRIPT;

    static {
        TICKET_BUY_LUA_SCRIPT = new DefaultRedisScript<>();
        TICKET_BUY_LUA_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("lua/ticket_buy.lua"));
        TICKET_BUY_LUA_SCRIPT.setResultType(Long.class);

        TOKEN_BUCKET_LUA_SCRIPT = new DefaultRedisScript<>();
        TOKEN_BUCKET_LUA_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("lua/token_bucket.lua"));
        TOKEN_BUCKET_LUA_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result<String> buy(TicketBuyDTO ticketBuyDTO) {
        // ===== 责任链校验 =====
        TicketValidateContext context = new TicketValidateContext();
        context.setTicketBuyDTO(ticketBuyDTO);
        AbstractTicketValidateHandler validateChain = ticketValidateChainBuilder.buildChain();
        validateChain.handle(context);
        if (!context.isPass()) {
            log.error("购票校验失败：{}，参数：{}", context.getErrorMsg(), ticketBuyDTO);
            return Result.error(context.getErrorMsg());
        }

        // 直接从校验上下文获取已查过的 BO，避免重复查缓存
        TicketListBO trainBO = context.getTicketListBO();

        // 提取参数
        LocalDate date = ticketBuyDTO.getDate();
        String trainCode = ticketBuyDTO.getCode();
        String startStation = ticketBuyDTO.getStartStation();
        String endStation = ticketBuyDTO.getEndStation();
        int seatTypeCode = ticketBuyDTO.getSeatType();
        SeatType seatType = SeatType.fromCode(seatTypeCode);
        List<TicketBuyDTO.Passenger> passengerList = ticketBuyDTO.getPassengerList();
        int passengerCount = passengerList.size();

        // 如果校验链没取 BO（降级），再从缓存取
        if (trainBO == null) {
            Cache cache = trainStopCacheManager.getCache("trainStopCache");
            if (cache == null) {
                return Result.error("系统异常");
            }
            String cacheKey = String.format("%s:%s", date, trainCode);
            trainBO = cache.get(cacheKey, TicketListBO.class);
            if (trainBO == null || CollectionUtils.isEmpty(trainBO.getStopoverStations())) {
                log.error("车次{}日期{}的JVM缓存无数据", trainCode, date);
                return Result.error("车次信息不存在");
            }
        }

        // 提取起止站序
        Integer startIndex = null, endIndex = null;
        for (TicketListBO.StopoverStation station : trainBO.getStopoverStations()) {
            if (startStation.equals(station.getStopoverStation())) startIndex = station.getStationIndex();
            if (endStation.equals(station.getStopoverStation())) endIndex = station.getStationIndex();
        }
        if (startIndex == null || endIndex == null || startIndex >= endIndex) {
            log.error("起止站序错误：{}→{}，站序{}→{}", startStation, endStation, startIndex, endIndex);
            return Result.error("经停站信息错误");
        }

        // 计算区间
        int startSection = startIndex;
        int endSection = endIndex - 1;
        List<Integer> sections = new ArrayList<>();
        for (int i = startSection; i <= endSection; i++) sections.add(i);
        String sectionsJson;
        try {
            sectionsJson = objectMapper.writeValueAsString(sections);
        } catch (JsonProcessingException e) {
            log.error("区间JSON序列化失败", e);
            sectionsJson = sections.toString();
        }
        int totalSectionCount = trainBO.getStopoverStations().size() - 1;
        if (totalSectionCount <= 0) {
            return Result.error("系统异常");
        }

        // Redis 库存检查
        String stockKey = String.format(STOCK_KEY_PREFIX, date, trainCode, seatTypeCode);
        List<String> sectionStrList = sections.stream().map(String::valueOf).collect(Collectors.toList());
        org.springframework.data.redis.core.HashOperations<String, String, String> hashOps = stringRedisTemplate.opsForHash();
        List<String> stockObjList = hashOps.multiGet(stockKey, sectionStrList);
        int minStock = stockObjList.stream()
                .filter(Objects::nonNull).mapToInt(Integer::parseInt).min().orElse(0);
        if (minStock < passengerCount) {
            log.warn("车次{}座位类型{}库存不足：最小库存{}，需{}", trainCode, seatTypeCode, minStock, passengerCount);
            return Result.error("余票不足");
        }

        // Token 桶限流
        String tokenKey = String.format(TOKEN_KEY_PREFIX, date, trainCode);
        Long tokenResult = stringRedisTemplate.execute(
                TOKEN_BUCKET_LUA_SCRIPT,
                Collections.singletonList(tokenKey),
                String.valueOf(passengerCount),
                String.valueOf(Math.max(minStock - 1, 0))
        );
        if (tokenResult != null && tokenResult < 0) {
            return Result.error("请求频繁，请稍后");
        }

        // 车厢数量
        int carNum = getCarNumFromCache(trainBO, seatTypeCode);
        if (carNum <= 0) {
            return Result.error("该座位类型无车厢");
        }

        // Redis 拉取位图
        String bitmapKey = String.format(BITMAP_KEY_PREFIX, date, trainCode, seatTypeCode);
        byte[] bitmapBytes = stringRedisTemplate.execute((RedisCallback<byte[]>) connection ->
                connection.get(bitmapKey.getBytes()));
        if (bitmapBytes == null) {
            return Result.error("系统异常");
        }

        // ===== V1 核心：合并遍历 + 随机起始 + 熔断 =====
        List<Integer> seatGlobalIndexList = getSeatGlobalIndexList(seatTypeCode);
        int totalSeats = carNum * seatGlobalIndexList.size();

        // 随机起始位置，分散并发冲突
        int startPos = ThreadLocalRandom.current().nextInt(totalSeats);
        long startTime = System.currentTimeMillis();
        int attempts = 0;

        int boughtCarRelIdx = -1, boughtSeatGlobalIdx = -1;

        for (int offset = 0; offset < totalSeats && attempts < MAX_ATTEMPTS; offset++) {
            // 超时熔断
            if (System.currentTimeMillis() - startTime > TIMEOUT_MS) {
                log.warn("购票超时熔断：车次{}，已尝试{}次", trainCode, attempts);
                return Result.error("系统繁忙，请重试");
            }

            // 从随机位置开始，循环遍历
            int pos = (startPos + offset) % totalSeats;
            int carRelativeIndex = pos / seatGlobalIndexList.size() + 1;
            int seatGlobalIndex = seatGlobalIndexList.get(pos % seatGlobalIndexList.size());

            long seatStartBit = calculateSeatStartBit(carRelativeIndex, seatGlobalIndex, totalSectionCount, seatTypeCode);

            // JVM 内存快速判断空闲
            if (!isSeatFreeInMemory(bitmapBytes, seatStartBit, startSection, endSection, totalSectionCount)) {
                continue;
            }

            attempts++;

            // 本地锁
            String localLockKey = String.format(LOCAL_LOCK_KEY_PREFIX, date, trainCode, seatTypeCode, carRelativeIndex, seatGlobalIndex);
            if (localLockMap.containsKey(localLockKey)) {
                continue;
            }
            ReentrantLock localLock = localLockMap.computeIfAbsent(localLockKey, k -> new ReentrantLock());
            boolean lockAcquired = false;
            try {
                lockAcquired = localLock.tryLock(0, TimeUnit.SECONDS);
                if (!lockAcquired) continue;

                // Lua 原子操作
                Long luaResult = stringRedisTemplate.execute(
                        TICKET_BUY_LUA_SCRIPT,
                        Arrays.asList(bitmapKey, stockKey),
                        String.valueOf(seatStartBit),
                        String.valueOf(startSection),
                        String.valueOf(endSection),
                        String.valueOf(totalSectionCount),
                        String.valueOf(passengerCount),
                        sectionsJson
                );

                if (luaResult == null) {
                    log.error("Lua脚本执行返回null");
                    continue;
                }
                if (luaResult == 1) {
                    boughtCarRelIdx = carRelativeIndex;
                    boughtSeatGlobalIdx = seatGlobalIndex;
                    break;
                }
                if (luaResult == -2) {
                    return Result.error("系统异常，请重试");
                }
                // luaResult == 0: 已被占，继续
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("获取本地锁中断", e);
            } finally {
                if (lockAcquired) {
                    localLock.unlock();
                    localLockMap.remove(localLockKey); // V1: 直接删除，不异步
                }
            }
        }

        if (boughtCarRelIdx < 0) {
            return Result.error(attempts >= MAX_ATTEMPTS ? "系统繁忙，请重试" : "无可用座位");
        }

        // ===== V1: 恢复订单落库 =====
        int finalCarAbsIdx = convertCarRelativeToAbsolute(boughtCarRelIdx, seatTypeCode);
        try {
            createOrder(date, trainCode, startStation, endStation, seatTypeCode,
                    finalCarAbsIdx, boughtSeatGlobalIdx, startSection, endSection,
                    totalSectionCount, passengerCount, sectionsJson, seatTypeCode,
                    passengerList, boughtCarRelIdx, seatGlobalIndexList);
        } catch (Exception e) {
            log.error("订单创建失败：车次{}，座位{}/{}", trainCode, finalCarAbsIdx, boughtSeatGlobalIdx, e);
            // 不回滚 Redis —— 由关单定时任务兜底回收
        }

        log.info("购票成功：车次{}，车厢{}，座位{}", trainCode, finalCarAbsIdx, boughtSeatGlobalIdx);
        return Result.success("排队中");
    }

    // ===================== V1: 同步创建订单 =====================

    @Transactional
    protected void createOrder(LocalDate date, String trainCode, String startStation, String endStation,
                               int seatTypeCode, int carriageNum, int seatNum,
                               int startSection, int endSection, int totalSectionCount,
                               int passengerCount, String sectionsJson, int seatStartBitCode,
                               List<TicketBuyDTO.Passenger> passengerList,
                               int carRelativeIndex, List<Integer> seatGlobalIndexList) {
        Order order = Order.builder()
                .userId(BaseContext.getCurrentId())
                .date(date).trainCode(trainCode)
                .startStation(startStation).endStation(endStation)
                .seatType(seatTypeCode).carriageNum(carriageNum).seatNum(seatNum)
                .startSection(startSection).endSection(endSection)
                .totalSectionCount(totalSectionCount).passengerCount(passengerCount)
                .sectionsJson(sectionsJson)
                .seatStartBit(calculateSeatStartBit(carRelativeIndex, seatNum, totalSectionCount, seatTypeCode))
                .status("UNPAID")
                .expireTime(LocalDateTime.now().plusMinutes(30))
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        orderMapper.insert(order);

        for (TicketBuyDTO.Passenger p : passengerList) {
            OrderPassenger op = OrderPassenger.builder()
                    .orderId(order.getId()).realName(p.getRealName()).idCard(p.getIdCard())
                    .build();
            orderPassengerMapper.insert(op);
        }
        log.info("订单创建成功：orderId={}, trainCode={}", order.getId(), trainCode);
    }

    // ===================== 辅助方法 =====================

    private boolean isSeatFreeInMemory(byte[] bitmapBytes, long seatStartBit,
                                       int userStartSection, int userEndSection, int totalSectionCount) {
        if (bitmapBytes == null || seatStartBit < 0 || userStartSection > userEndSection) return false;
        for (int section = userStartSection; section <= userEndSection; section++) {
            long bitOffset = seatStartBit + (section - 1);
            int byteIndex = (int) (bitOffset / 8);
            int bitInByte = (int) (bitOffset % 8);
            if (byteIndex >= bitmapBytes.length) return false;
            if ((bitmapBytes[byteIndex] & (1 << bitInByte)) != 0) return false;
        }
        return true;
    }

    private int getCarNumFromCache(TicketListBO trainBO, int seatTypeCode) {
        if (trainBO == null) return 0;
        return switch (SeatType.fromCode(seatTypeCode)) {
            case BUSINESS -> Optional.ofNullable(trainBO.getBusinessCarriageInfo())
                    .map(i -> i.getCarriageIndexes().size()).orElse(0);
            case FIRST -> Optional.ofNullable(trainBO.getFirstClassCarriageInfo())
                    .map(i -> i.getCarriageIndexes().size()).orElse(0);
            case SECOND -> Optional.ofNullable(trainBO.getSecondClassCarriageInfo())
                    .map(i -> i.getCarriageIndexes().size()).orElse(0);
        };
    }

    private List<Integer> getSeatGlobalIndexList(int seatTypeCode) {
        return switch (SeatType.fromCode(seatTypeCode)) {
            case BUSINESS -> BUSINESS_SEAT_GLOBAL_INDEX;
            case FIRST -> FIRST_SEAT_GLOBAL_INDEX;
            case SECOND -> SECOND_SEAT_GLOBAL_INDEX;
        };
    }

    private long calculateSeatStartBit(int carRelativeIndex, int seatGlobalIndex, int totalSectionCount, int seatTypeCode) {
        int seatPerCar = SeatType.fromCode(seatTypeCode).getSeatsPerCarriage();
        return (long) (carRelativeIndex - 1) * seatPerCar * totalSectionCount
                + (long) (seatGlobalIndex - 1) * totalSectionCount;
    }

    private int convertCarRelativeToAbsolute(int carRelativeIndex, int seatTypeCode) {
        return switch (SeatType.fromCode(seatTypeCode)) {
            case BUSINESS -> 1;
            case FIRST -> 2;
            case SECOND -> carRelativeIndex + 2;
        };
    }

    private static List<Integer> initFirstSeatGlobalIndex() {
        List<Integer> list = new ArrayList<>(28);
        for (int i = 1; i <= 28; i++) list.add(i);
        return list;
    }

    private static List<Integer> initSecondSeatGlobalIndex() {
        List<Integer> list = new ArrayList<>(90);
        for (int i = 1; i <= 90; i++) list.add(i);
        return list;
    }
}
