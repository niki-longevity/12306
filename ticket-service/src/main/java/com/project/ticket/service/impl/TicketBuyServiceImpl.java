package com.project.ticket.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ticket.handler.builder.TicketValidateChainBuilder;
// TODO: Re-enable when rocketmq-spring-boot-starter is available
// import org.apache.rocketmq.spring.core.RocketMQTemplate;
import com.project.ticket.handler.chain.AbstractTicketValidateHandler;
import com.project.ticket.utils.TicketValidateContext;
import com.project.ticket.mapper.TrainCarriageMapper;
import com.project.ticket.pojo.bo.TicketListBO;
import com.project.ticket.pojo.dto.TicketBuyDTO;
// TODO: Will be used when order-service HTTP calls are implemented
// import com.project.common.pojo.entity.Order;
// import com.project.common.pojo.entity.OrderPassenger;
import com.project.ticket.pojo.entity.TrainCarriage;
import com.project.ticket.pojo.enums.SeatType;
// TODO: OrderService will be called via HTTP in microservice architecture
// import com.project.order.service.OrderService;
import com.project.ticket.service.TicketBuyService;
// TODO: Will be used when order-service HTTP calls are implemented
// import com.project.common.utils.BaseContext;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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

    // ===== 座位全局顺序编号定义 =====
    private static final List<Integer> BUSINESS_SEAT_GLOBAL_INDEX = Arrays.asList(1, 2, 3, 4, 5);
    private static final List<Integer> FIRST_SEAT_GLOBAL_INDEX = initFirstSeatGlobalIndex();
    private static final List<Integer> SECOND_SEAT_GLOBAL_INDEX = initSecondSeatGlobalIndex();

    // ===== 依赖注入 =====
    private final StringRedisTemplate stringRedisTemplate;
    private final CacheManager trainStopCacheManager;
    private final TrainCarriageMapper trainCarriageMapper;
    private final ObjectMapper objectMapper;

    // 责任链构造器
    private final TicketValidateChainBuilder ticketValidateChainBuilder;

    // 订单服务
    // TODO: OrderService will be called via HTTP in microservice architecture
    // private final OrderService orderService;

    // RocketMQ 延时消息（测试环境可能不可用）
    // TODO: Re-enable when rocketmq-spring-boot-starter is available
    // @org.springframework.beans.factory.annotation.Autowired(required = false)
    // private RocketMQTemplate rocketMQTemplate;

    // 本地锁
    private final ConcurrentHashMap<String, ReentrantLock> localLockMap = new ConcurrentHashMap<>();

    // 令牌桶原子操作Lua脚本
    private static final DefaultRedisScript<Long> TOKEN_BUCKET_LUA_SCRIPT;

    // 购票核心Lua脚本
    private static final DefaultRedisScript<Long> TICKET_BUY_LUA_SCRIPT;

    // 延迟删除锁对象的线程池
    private final ScheduledExecutorService lockDelayExecutor = Executors.newScheduledThreadPool(
            1, // 核心线程数：1个足够，高并发可设为2
            new ThreadFactory() {
                private final AtomicInteger threadNum = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "lock-delay-executor-" + threadNum.getAndIncrement());
                    t.setDaemon(true); // 守护线程：项目关闭时自动退出，避免内存泄漏
                    return t;
                }
            }
    );

    static {
        TICKET_BUY_LUA_SCRIPT = new DefaultRedisScript<>();
        TICKET_BUY_LUA_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("lua/ticket_buy.lua"));
        TICKET_BUY_LUA_SCRIPT.setResultType(Long.class);

        TOKEN_BUCKET_LUA_SCRIPT = new DefaultRedisScript<>();
        TOKEN_BUCKET_LUA_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("lua/token_bucket.lua"));
        TOKEN_BUCKET_LUA_SCRIPT.setResultType(Long.class);
    }

    /**
     * 购票
     *
     * @param ticketBuyDTO 购票参数
     * @return 购票结果
     */
    @Override
    public String buy(TicketBuyDTO ticketBuyDTO) {
        // ===== 责任链校验 =====
        // 校验参数是否为空、校验车次是否存在、校验车站在车次中是否合法
        // 1. 构建校验上下文
        TicketValidateContext context = new TicketValidateContext();
        context.setTicketBuyDTO(ticketBuyDTO);
        // 2. 获取校验链条并执行
        AbstractTicketValidateHandler validateChain = ticketValidateChainBuilder.buildChain();
        validateChain.handle(context);
        // 3. 校验结果判断
        if (!context.isPass()) {
            log.error("购票校验失败：{}，参数：{}", context.getErrorMsg(), ticketBuyDTO);
            return context.getErrorMsg();
        }

        // 1、提取参数
        LocalDate date = ticketBuyDTO.getDate();
        String trainCode = ticketBuyDTO.getCode();
        String startStation = ticketBuyDTO.getStartStation();
        String endStation = ticketBuyDTO.getEndStation();
        int seatType = ticketBuyDTO.getSeatType();
        List<TicketBuyDTO.Passenger> passengerList = ticketBuyDTO.getPassengerList();
        int passengerCount = passengerList.size();

        // 2、查本地内存，提取站序、区间等信息
        // TODO 待优化：只缓存热点数据，减少内存占用；并运行查询 Redis 重建缓存
        Cache cache = trainStopCacheManager.getCache("trainStopCache");
        if (cache == null) {
            log.error("JVM缓存实例获取失败");
            return "系统异常";
        }
        String cacheKey = String.format("%s:%s", date, trainCode);
        TicketListBO trainBO = cache.get(cacheKey, TicketListBO.class);
        if (trainBO == null || CollectionUtils.isEmpty(trainBO.getStopoverStations())) {
            log.error("车次{}日期{}的JVM缓存无数据", trainCode, date);
            return "车次信息不存在";
        }

        // 提取起止站序
        Integer startIndex = null, endIndex = null;
        for (TicketListBO.StopoverStation station : trainBO.getStopoverStations()) {
            if (startStation.equals(station.getStopoverStation())) {
                startIndex = station.getStationIndex();
            }
            if (endStation.equals(station.getStopoverStation())) {
                endIndex = station.getStationIndex();
            }
        }
        if (startIndex == null || endIndex == null || startIndex >= endIndex) {
            log.error("起止站序错误：{}→{}，站序{}→{}", startStation, endStation, startIndex, endIndex);
            return "经停站信息错误";
        }

        // 计算区间列表
        int startSection = startIndex;
        int endSection = endIndex - 1;
        int sectionCount = endSection - startSection + 1;
        List<Integer> sections = new ArrayList<>();
        for (int i = startSection; i <= endSection; i++) {
            sections.add(i);
        }
        String sectionsJson;
        try {
            sectionsJson = objectMapper.writeValueAsString(sections);
        } catch (JsonProcessingException e) {
            log.error("区间JSON序列化失败", e);
            sectionsJson = sections.toString();
        }

        // 获取车次总区间数（总站点数-1）
        int totalStationCount = trainBO.getStopoverStations().size();
        int totalSectionCount = totalStationCount - 1; // 总区间数=总站点数-1
        if (totalSectionCount <= 0) {
            log.error("车次{}总区间数异常：{}", trainCode, totalSectionCount);
            return "系统异常";
        }

        // 3、查Redis库存，1次hmget命令
        String stockKey = String.format(STOCK_KEY_PREFIX, date, trainCode, seatType);
        org.springframework.data.redis.core.HashOperations<String, String, String> hashOps = stringRedisTemplate.opsForHash();
        List<String> sectionStrList = sections.stream().map(String::valueOf).collect(Collectors.toList());
        List<String> stockObjList = hashOps.multiGet(stockKey, sectionStrList);

        // 获取最小库存
        int minStock = stockObjList.stream()
                .filter(Objects::nonNull)
                .mapToInt(Integer::parseInt)
                .min()
                .orElse(0);
        if (minStock < passengerCount) {
            log.warn("车次{}座位类型{}库存不足：最小库存{}，需{}", trainCode, seatType, minStock, passengerCount);
            return "余票不足";
        }

        // 4、获取令牌，令牌不足则返回
        String tokenKey = String.format(TOKEN_KEY_PREFIX, date, trainCode);
        Long tokenResult = stringRedisTemplate.execute(
                TOKEN_BUCKET_LUA_SCRIPT,
                Collections.singletonList(tokenKey),
                String.valueOf(passengerCount),
                String.valueOf(Math.max(minStock - 1, 0))
        );
        if (tokenResult != null && tokenResult < 0) {
            return "请求频繁，请稍后";
        }

        // 5、从JVM缓存获取车厢信息
        int carNum = getCarNumFromCache(trainBO, seatType);
        if (carNum <= 0) {
            log.error("车次{}座位类型{}无车厢信息", trainCode, seatType);
            return "该座位类型无车厢";
        }

        // 6、1次Redis命令拉取 “车次+座位类型” 级别的位图
        String bitmapKey = String.format(BITMAP_KEY_PREFIX, date, trainCode, seatType);
        byte[] bitmapBytes = stringRedisTemplate.execute((RedisCallback<byte[]>) connection ->
                connection.get(bitmapKey.getBytes())
        );
        if (bitmapBytes == null) {
            log.error("位图不存在：{}", bitmapKey);
            return "系统异常";
        }

        // 7、本地内存一次性筛选所有符合条件的空闲座位，彻底消除无效Lua执行
        List<Integer> seatGlobalIndexList = getSeatGlobalIndexList(seatType);
        List<SeatInfo> freeSeatList = new ArrayList<>();

        // 遍历所有车厢+座位，按「每个座位占totalSectionCount个bit」的规则计算bit位
        // TODO 遍历逻辑有待优化，可以选择随机位置开始遍历，减少并发冲突
        for (int carRelativeIndex = 1; carRelativeIndex <= carNum; carRelativeIndex++) {
            for (int seatGlobalIndex : seatGlobalIndexList) {
                // 计算该座位的起始bit位
                long seatStartBit = calculateSeatStartBit(carRelativeIndex, seatGlobalIndex, totalSectionCount, seatType);

                // 判断规则：该座位的totalSectionCount个bit中，乘客的[startSection, endSection]区间全为0
                boolean isFree = isSeatFreeInMemory(
                        bitmapBytes,
                        seatStartBit,        // 座位起始bit
                        startSection,        // 乘客乘车起始区间（比如3）
                        endSection,          // 乘客乘车结束区间（比如8）
                        totalSectionCount    // 车次总区间数（比如19）
                );

                if (isFree) {
                    freeSeatList.add(new SeatInfo(carRelativeIndex, seatGlobalIndex, seatStartBit));
                }
            }
        }

        // 无空闲座位直接返回
        if (CollectionUtils.isEmpty(freeSeatList)) {
            return "无可用座位";
        }

        // 8、遍历筛选后的空闲座位
        // TODO 待优化：融合上面第七部
        boolean buySuccess = false;
        int finalCarAbsoluteIndex = -1;
        int finalSeatGlobalIndex = -1;

        for (SeatInfo freeSeat : freeSeatList) {
            // 构建锁对象
            String localLockKey = String.format(LOCAL_LOCK_KEY_PREFIX, date, trainCode, seatType, freeSeat.carRelativeIndex,freeSeat.seatGlobalIndex);
            // 锁对象存在就跳过
            if (localLockMap.containsKey(localLockKey)) {
                continue;
            }
            // 创建锁对象
            ReentrantLock localLock = localLockMap.computeIfAbsent(localLockKey, k -> new ReentrantLock());
            boolean localLockAcquired = false;

            try {
                // 获取本地锁，获取锁失败就跳过
                localLockAcquired = localLock.tryLock(0, TimeUnit.SECONDS);
                if (!localLockAcquired) {
                    continue;
                }

                // 执行全局单例Lua脚本（修正参数传递）
                Long luaResult = stringRedisTemplate.execute(
                        TICKET_BUY_LUA_SCRIPT,
                        Arrays.asList(bitmapKey, stockKey),  // KEYS[1]=bitmapKey, KEYS[2]=stockKey
                        // ARGV 顺序必须严格匹配 Lua 脚本：
                        String.valueOf(freeSeat.seatStartBit),  // ARGV[1]：座位起始bit
                        String.valueOf(startSection),            // ARGV[2]：乘客起始区间（比如3）
                        String.valueOf(endSection),              // ARGV[3]：乘客结束区间（比如8）
                        String.valueOf(totalSectionCount),       // ARGV[4]：总区间数（比如19）
                        String.valueOf(passengerCount),          // ARGV[5]：乘客数
                        sectionsJson                             // ARGV[6]：区间列表JSON
                );

                if (luaResult == null) {
                    log.error("Lua脚本执行返回null");
                    continue;
                }
                if (luaResult == 1) {
                    // 购票成功
                    finalCarAbsoluteIndex = convertCarRelativeToAbsolute(freeSeat.carRelativeIndex, seatType);
                    finalSeatGlobalIndex = freeSeat.seatGlobalIndex;
                    buySuccess = true;

                    // TODO: 微服务架构下，订单创建将通过HTTP调用order-service
                    // 创建订单（同步落库）
                    // Order order = Order.builder()
                    //         .userId(BaseContext.getCurrentId())
                    //         .date(date)
                    //         .trainCode(trainCode)
                    //         .startStation(startStation)
                    //         .endStation(endStation)
                    //         .seatType(seatType)
                    //         .carriageNum(finalCarAbsoluteIndex)
                    //         .seatNum(finalSeatGlobalIndex)
                    //         .startSection(startSection)
                    //         .endSection(endSection)
                    //         .totalSectionCount(totalSectionCount)
                    //         .passengerCount(passengerCount)
                    //         .sectionsJson(sectionsJson)
                    //         .seatStartBit(freeSeat.seatStartBit)
                    //         .build();
                    // List<OrderPassenger> orderPassengers = passengerList.stream()
                    //         .map(p -> OrderPassenger.builder()
                    //                 .realName(p.getRealName())
                    //                 .idCard(p.getIdCard())
                    //                 .build())
                    //         .collect(java.util.stream.Collectors.toList());
                    // orderService.create(order, orderPassengers);
                    //
                    // // 发送延时关单消息（delayLevel=16 → 30分钟）
                    // if (rocketMQTemplate != null) {
                    //     try {
                    //         org.springframework.messaging.Message<String> msg = org.springframework.messaging.support.MessageBuilder
                    //                 .withPayload(String.valueOf(order.getId()))
                    //                 .build();
                    //         rocketMQTemplate.syncSend("order-close-topic", msg, 3000, 16);
                    //     } catch (Exception e) {
                    //         log.error("发送延时关单消息失败：orderId={}", order.getId(), e);
                    //         // 不阻塞购票流程，定时任务会兜底
                    //     }
                    // }

                    log.info("购票成功：车次{}，车厢{}，座位{}", trainCode, finalCarAbsoluteIndex, finalSeatGlobalIndex);
                    break;
                }
                if (luaResult == 0) {
                    log.warn("该座位已被占，请继续下一个座位……");
                    continue;
                }
                if (luaResult == -2) {
                    log.error("区间解析失败：{}", sectionsJson);
                    return "系统异常，请重试";
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("获取本地锁中断", e);
            } finally {
                // 解锁后延迟1s删除锁对象
                if (localLockAcquired) {
                    localLock.unlock();
                    // 提交延迟任务：1s后从Map中删除锁对象（平衡限流和内存）
                    lockDelayExecutor.schedule(() -> {
                        try {
                            // 移除锁对象：仅当锁未被持有才删除（避免删除正在使用的锁）
                            ReentrantLock lockToRemove = localLockMap.get(localLockKey);
                            if (lockToRemove != null && !lockToRemove.isLocked()) {
                                localLockMap.remove(localLockKey);
                                log.debug("延迟删除锁对象：{}", localLockKey);
                            }
                        } catch (Exception e) {
                            log.error("延迟删除锁对象失败：{}", localLockKey, e);
                        }
                    }, 1, TimeUnit.SECONDS);
                }
            }
        }

        // 9、最终结果处理
        if (buySuccess) {
            return "排队中";
        } else {
            return "无可用座位";
        }
    }

    // ===================== 核心方法 =====================

    /**
     * 【重构】判断座位是否空闲（匹配位图设计）
     * @param bitmapBytes 整个位图的字节数组
     * @param seatStartBit 该座位的起始bit位（总区间数×座位序号）
     * @param userStartSection 乘客乘车起始区间
     * @param userEndSection 乘客乘车结束区间
     * @param totalSectionCount 车次总区间数
     * @return true=空闲（乘客区间的bit全为0）
     */
    private boolean isSeatFreeInMemory(byte[] bitmapBytes, long seatStartBit,
                                       int userStartSection, int userEndSection, int totalSectionCount) {
        if (bitmapBytes == null || seatStartBit < 0 || userStartSection > userEndSection) {
            return false;
        }

        // 遍历乘客乘车的所有区间，检查对应bit位是否全为0
        for (int section = userStartSection; section <= userEndSection; section++) {
            // 计算该区间在座位bit组中的偏移量（区间从1开始，bit偏移从0开始）
            long bitOffset = seatStartBit + (section - 1);

            // 计算该bit位所在的字节索引和字节内偏移
            int byteIndex = (int) (bitOffset / 8);
            int bitInByte = (int) (bitOffset % 8);

            // 超出位图长度 → 判定为已售
            if (byteIndex >= bitmapBytes.length) {
                return false;
            }

            // 检查该bit位是否为1（已售），只要有一个为1就返回false
            if ((bitmapBytes[byteIndex] & (1 << bitInByte)) != 0) {
                return false;
            }
        }

        // 所有乘客区间的bit位都为0 → 空闲
        return true;
    }

    // ===== 项目关闭时销毁线程池（关键：避免内存泄漏） =====
    @PreDestroy
    public void destroyExecutor() {
        // 优雅关闭：停止接收新任务，等待已提交任务执行完成（最多等5s）
        lockDelayExecutor.shutdown();
        try {
            if (!lockDelayExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                lockDelayExecutor.shutdownNow(); // 强制关闭
            }
        } catch (InterruptedException e) {
            lockDelayExecutor.shutdownNow();
        }
    }

    /**
     * 座位信息封装
     */
    private static class SeatInfo {
        private final int carRelativeIndex;
        private final int seatGlobalIndex;
        private final long seatStartBit;

        public SeatInfo(int carRelativeIndex, int seatGlobalIndex, long seatStartBit) {
            this.carRelativeIndex = carRelativeIndex;
            this.seatGlobalIndex = seatGlobalIndex;
            this.seatStartBit = seatStartBit;
        }
    }

    /**
     * 从JVM缓存的TicketListBO中获取对应座位类型的车厢数量
     */
    private int getCarNumFromCache(TicketListBO trainBO, int seatTypeCode) {
        if (trainBO == null) return 0;
        SeatType st = SeatType.fromCode(seatTypeCode);
        return switch (st) {
            case BUSINESS -> Optional.ofNullable(trainBO.getBusinessCarriageInfo())
                    .map(info -> info.getCarriageIndexes().size()).orElse(0);
            case FIRST -> Optional.ofNullable(trainBO.getFirstClassCarriageInfo())
                    .map(info -> info.getCarriageIndexes().size()).orElse(0);
            case SECOND -> Optional.ofNullable(trainBO.getSecondClassCarriageInfo())
                    .map(info -> info.getCarriageIndexes().size()).orElse(0);
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
        for (int i = 1; i <= 28; i++) {
            list.add(i);
        }
        return list;
    }

    private static List<Integer> initSecondSeatGlobalIndex() {
        List<Integer> list = new ArrayList<>(90);
        for (int i = 1; i <= 90; i++) {
            list.add(i);
        }
        return list;
    }
}