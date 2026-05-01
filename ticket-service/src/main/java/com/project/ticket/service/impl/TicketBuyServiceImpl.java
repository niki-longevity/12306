package com.project.ticket.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.result.Result;
import com.project.common.utils.BaseContext;
import com.project.ticket.handler.builder.TicketValidateChainBuilder;
import com.project.ticket.handler.chain.AbstractTicketValidateHandler;
import com.project.ticket.utils.TicketValidateContext;
import com.project.ticket.pojo.bo.TicketListBO;
import com.project.ticket.pojo.dto.TicketBuyDTO;
import com.project.ticket.pojo.enums.SeatType;
import com.project.ticket.service.TicketBuyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@org.springframework.context.annotation.Profile("!bench-outbox & !bench-http")
public class TicketBuyServiceImpl implements TicketBuyService {

    // ===== 基础配置 =====
    private static final String TOKEN_KEY_PREFIX = "Token:%s:%s:%d";
    private static final String STOCK_KEY_PREFIX = "Stock:%s:%s:%d";
    private static final String BITMAP_KEY_PREFIX = "%s:%s:%d:bitmap";
    private static final String LOCK_KEY_PREFIX = "Lock:%s:%s:%d:%d:%d";

    // V2: 自适应熔断 — 票多时限搜索次数，票少时放开
    private static final int DEFAULT_MAX_ATTEMPTS = 100;
    private static final int LOW_STOCK_THRESHOLD_MULTIPLIER = 3;

    // ===== 座位全局顺序编号定义 =====
    private static final List<Integer> BUSINESS_SEAT_GLOBAL_INDEX = Arrays.asList(1, 2, 3, 4, 5);
    private static final List<Integer> FIRST_SEAT_GLOBAL_INDEX = initFirstSeatGlobalIndex();
    private static final List<Integer> SECOND_SEAT_GLOBAL_INDEX = initSecondSeatGlobalIndex();

    // ===== 依赖注入 =====
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final CacheManager trainStopCacheManager;
    private final ObjectMapper objectMapper;
    private final TicketValidateChainBuilder ticketValidateChainBuilder;
    private final RocketMQTemplate rocketMQTemplate;

    // 本地锁（JVM 内互斥，作为分布式锁的第一道防线）
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

        TicketListBO trainBO = context.getTicketListBO();

        // 提取参数
        LocalDate date = ticketBuyDTO.getDate();
        String trainCode = ticketBuyDTO.getCode();
        String startStation = ticketBuyDTO.getStartStation();
        String endStation = ticketBuyDTO.getEndStation();
        int seatTypeCode = ticketBuyDTO.getSeatType();
        List<TicketBuyDTO.Passenger> passengerList = ticketBuyDTO.getPassengerList();
        int passengerCount = passengerList.size();

        // 校验链没取到 BO，从缓存取
        if (trainBO == null) {
            Cache cache = trainStopCacheManager.getCache("trainStopCache");
            if (cache == null) return Result.error("系统异常");
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
        if (totalSectionCount <= 0) return Result.error("系统异常");

        // ===== Redis 库存检查 =====
        String stockKey = String.format(STOCK_KEY_PREFIX, date, trainCode, seatTypeCode);
        org.springframework.data.redis.core.HashOperations<String, String, String> hashOps = stringRedisTemplate.opsForHash();
        List<String> sectionStrList = sections.stream().map(String::valueOf).collect(Collectors.toList());
        List<String> stockObjList = hashOps.multiGet(stockKey, sectionStrList);
        int minStock = stockObjList.stream()
                .filter(Objects::nonNull).mapToInt(Integer::parseInt).min().orElse(0);
        if (minStock < passengerCount) {
            log.warn("车次{}座位类型{}库存不足", trainCode, seatTypeCode);
            return Result.error("余票不足");
        }

        // ===== Token 桶限流 =====
        String tokenKey = String.format(TOKEN_KEY_PREFIX, date, trainCode, seatTypeCode);
        Long tokenResult = stringRedisTemplate.execute(
                TOKEN_BUCKET_LUA_SCRIPT,
                Collections.singletonList(tokenKey),
                String.valueOf(passengerCount)
        );
        if (tokenResult != null && tokenResult < 0) {
            return Result.error("请求频繁，请稍后");
        }

        // 车厢数量
        int carNum = getCarNumFromCache(trainBO, seatTypeCode);
        if (carNum <= 0) return Result.error("该座位类型无车厢");

        // ===== Redis 拉取位图 =====
        String bitmapKey = String.format(BITMAP_KEY_PREFIX, date, trainCode, seatTypeCode);
        byte[] bitmapBytes = stringRedisTemplate.execute((RedisCallback<byte[]>) connection ->
                connection.get(bitmapKey.getBytes()));
        if (bitmapBytes == null) return Result.error("系统异常");

        // ===== V2: 自适应熔断 =====
        int maxAttempts = (minStock < passengerCount * LOW_STOCK_THRESHOLD_MULTIPLIER)
                ? Integer.MAX_VALUE   // 票少：不限制，允许扫完所有座位
                : DEFAULT_MAX_ATTEMPTS;

        List<Integer> seatGlobalIndexList = getSeatGlobalIndexList(seatTypeCode);
        int totalSeats = carNum * seatGlobalIndexList.size();
        int startPos = ThreadLocalRandom.current().nextInt(totalSeats);
        int attempts = 0;
        int boughtCarRelIdx = -1, boughtSeatGlobalIdx = -1;

        for (int offset = 0; offset < totalSeats && attempts < maxAttempts; offset++) {
            int pos = (startPos + offset) % totalSeats;
            int carRelativeIndex = pos / seatGlobalIndexList.size() + 1;
            int seatGlobalIndex = seatGlobalIndexList.get(pos % seatGlobalIndexList.size());

            long seatStartBit = calculateSeatStartBit(carRelativeIndex, seatGlobalIndex, totalSectionCount, seatTypeCode);

            // V2: 一次提取 + 一次 AND 判断空闲
            if (!isSeatFreeInMemory(bitmapBytes, seatStartBit, startSection, endSection)) {
                continue;
            }

            attempts++;

            String lockKey = String.format(LOCK_KEY_PREFIX, date, trainCode, seatTypeCode, carRelativeIndex, seatGlobalIndex);

            // ===== V2: 双锁机制 =====
            // 第一道：本地锁 (JVM 内互斥)
            ReentrantLock localLock = localLockMap.computeIfAbsent(lockKey, k -> new ReentrantLock());
            boolean localLocked = false;
            RLock distLock = null;
            boolean distLocked = false;

            try {
                localLocked = localLock.tryLock(0, TimeUnit.SECONDS);
                if (!localLocked) continue;

                // 第二道：分布式锁 (跨 JVM 互斥，Redisson watchdog 自动续期)
                distLock = redissonClient.getLock(lockKey);
                distLocked = distLock.tryLock(100, TimeUnit.MILLISECONDS);
                if (!distLocked) continue;

                // Lua 原子操作 — 最终裁判
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
                // luaResult == 0: 已被占
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("锁获取中断", e);
            } finally {
                if (distLocked && distLock != null) {
                    try { distLock.unlock(); } catch (Exception ignored) {}
                }
                if (localLocked) {
                    localLock.unlock();
                    localLockMap.remove(lockKey);
                }
            }
        }

        if (boughtCarRelIdx < 0) {
            return Result.error(attempts >= maxAttempts ? "系统繁忙，请重试" : "无可用座位");
        }

        int finalCarAbsIdx = convertCarRelativeToAbsolute(boughtCarRelIdx, seatTypeCode);
        long seatStartBitVal = calculateSeatStartBit(boughtCarRelIdx, boughtSeatGlobalIdx, totalSectionCount, seatTypeCode);
        postLuaSuccess(date, trainCode, startStation, endStation, seatTypeCode, finalCarAbsIdx,
                boughtSeatGlobalIdx, startSection, endSection, totalSectionCount, passengerCount,
                sectionsJson, seatStartBitVal, passengerList);

        log.info("购票成功：车次{}，车厢{}，座位{}", trainCode, finalCarAbsIdx, boughtSeatGlobalIdx);
        return Result.success("排队中");
    }

    /** Subclasses override this to implement different order-creation strategies */
    protected void postLuaSuccess(LocalDate date, String trainCode, String startStation, String endStation,
                                   int seatTypeCode, int carriageNum, int seatNum, int startSection, int endSection,
                                   int totalSectionCount, int passengerCount, String sectionsJson, long seatStartBit,
                                   List<TicketBuyDTO.Passenger> passengerList) {
        try {
            Map<String, Object> orderPayload = new HashMap<>();
            orderPayload.put("userId", BaseContext.getCurrentId() != null ? BaseContext.getCurrentId() : 2050050560936701953L);
            orderPayload.put("date", date.toString());
            orderPayload.put("trainCode", trainCode);
            orderPayload.put("startStation", startStation);
            orderPayload.put("endStation", endStation);
            orderPayload.put("seatType", seatTypeCode);
            orderPayload.put("carriageNum", carriageNum);
            orderPayload.put("seatNum", seatNum);
            orderPayload.put("startSection", startSection);
            orderPayload.put("endSection", endSection);
            orderPayload.put("totalSectionCount", totalSectionCount);
            orderPayload.put("passengerCount", passengerCount);
            orderPayload.put("sectionsJson", sectionsJson);
            orderPayload.put("seatStartBit", seatStartBit);

            List<Map<String, String>> passengers = passengerList.stream()
                    .map(p -> { Map<String, String> m = new HashMap<>(); m.put("realName",p.getRealName()); m.put("idCard",p.getIdCard()); return m; })
                    .collect(Collectors.toList());
            orderPayload.put("passengers", passengers);
            String payloadJson = objectMapper.writeValueAsString(orderPayload);

            var msg = MessageBuilder.withPayload(payloadJson).build();
            rocketMQTemplate.sendMessageInTransaction("order-create-topic", msg, null);

            var closeMsg = MessageBuilder.withPayload(payloadJson).build();
            rocketMQTemplate.syncSend("order-close-topic", closeMsg, 3000, 16);
        } catch (Exception e) {
            log.error("MQ发送失败：车次{}", trainCode, e);
        }
    }

    // ===================== V2: 位图空闲判断 — 一次提取 + 一次 AND =====================

    /**
     * 判断座位在乘客乘车区间是否空闲。
     * 位图结构：每个座位占 totalSectionCount 个 bit。
     * 座位 k 的 bit 在 [k * totalSectionCount, (k+1) * totalSectionCount - 1]。
     *
     * @param bitmapBytes      整个位图字节数组
     * @param seatStartBit     该座位在位图中的起始 bit 索引
     * @param userStartSection 乘客乘车起始区间号（1-based）
     * @param userEndSection   乘客乘车结束区间号（1-based），对应区间 [start, end] 共 rangeBits 个 bit
     * @return true=空闲（乘客区间内所有 bit 均为 0）
     */
    private boolean isSeatFreeInMemory(byte[] bitmapBytes, long seatStartBit,
                                       int userStartSection, int userEndSection) {
        if (bitmapBytes == null || seatStartBit < 0 || userStartSection > userEndSection) {
            return false;
        }

        // 1. 计算乘客区间对应的绝对 bit 范围
        long rangeStartBit = seatStartBit + userStartSection - 1;
        long rangeEndBit   = seatStartBit + userEndSection - 1;
        int  rangeBits     = userEndSection - userStartSection + 1;

        int startByte = (int) (rangeStartBit / 8);
        int endByte   = (int) (rangeEndBit / 8);

        if (endByte >= bitmapBytes.length) return false;

        // 2. 一次读取 1~3 个字节拼成 int
        int value = 0;
        for (int i = 0; i <= endByte - startByte; i++) {
            value |= (bitmapBytes[startByte + i] & 0xFF) << (i * 8);
        }

        // 3. 构建 mask：rangeBits 个连续的 1，放到 value 内的正确位置
        int startBitInValue = (int) (rangeStartBit % 8);
        int mask = ((1 << rangeBits) - 1) << startBitInValue;

        // 4. 一次 AND 完成判断
        return (value & mask) == 0;
    }

    // ===================== 辅助方法 =====================

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
