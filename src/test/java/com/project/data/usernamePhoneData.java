package com.project.data;

import com.project.pojo.entity.User;
import com.project.pojo.entity.UsernamePhone;
import com.project.mapper.UserMapper;
import com.project.service.UsernamePhoneService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发同步分库分表user表的username+phone到username_phone路由表
 */
@SpringBootTest
public class usernamePhoneData {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UsernamePhoneService usernamePhoneService;

    // 线程池参数
    private static final int CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;
    private static final int MAX_POOL_SIZE = CORE_POOL_SIZE * 2;
    private static final long KEEP_ALIVE_TIME = 60L;

    // 批量写入批次大小（MP的saveBatch默认适配此大小，建议500-1000）
    private static final int BATCH_SIZE = 500;

    @Test
    public void syncUsernamePhoneToRouteTable() throws InterruptedException {
        // 1. 全量查询分库分表的user数据
        System.out.println("开始查询所有分库分表的user数据...");
        List<User> allUserList = userMapper.selectList(null);
        if (allUserList == null || allUserList.isEmpty()) {
            System.out.println("分库分表中无user数据，同步结束");
            return;
        }
        System.out.println("原始user数据总量：" + allUserList.size());

        // 2. 数据预处理：转换+去重+过滤空值
        List<UsernamePhone> rawRouteList = allUserList.stream()
                .filter(user ->
                        (user.getUsername() != null && !user.getUsername().trim().isEmpty())
                                || (user.getPhone() != null && !user.getPhone().trim().isEmpty())
                )
                .map(user -> UsernamePhone.builder()
                        .username(user.getUsername() == null ? "" : user.getUsername().trim())
                        .phone(user.getPhone() == null ? "" : user.getPhone().trim())
                        .build())
                .toList();

        // 去重：按username+phone联合去重
        Set<String> uniqueKeySet = new HashSet<>();
        List<UsernamePhone> validRouteList = new ArrayList<>();
        for (UsernamePhone up : rawRouteList) {
            String uniqueKey = up.getUsername() + "|" + up.getPhone();
            if (!uniqueKeySet.contains(uniqueKey)) {
                uniqueKeySet.add(uniqueKey);
                validRouteList.add(up);
            }
        }
        int totalValidCount = validRouteList.size();
        System.out.println("去重后有效路由数据量：" + totalValidCount);
        if (totalValidCount == 0) {
            System.out.println("无有效路由数据，同步结束");
            return;
        }

        // 3. 初始化线程池
        ExecutorService executorService = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10000),
                r -> new Thread(r, "route-sync-thread-" + UUID.randomUUID().toString().substring(0, 8)),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // 4. 并发分片写入（核心修改：用MP的saveBatch）
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        CountDownLatch countDownLatch = new CountDownLatch((totalValidCount + BATCH_SIZE - 1) / BATCH_SIZE);

        for (int i = 0; i < totalValidCount; i += BATCH_SIZE) {
            int start = i;
            int end = Math.min(i + BATCH_SIZE, totalValidCount);
            List<UsernamePhone> batchList = validRouteList.subList(start, end);

            executorService.submit(() -> {
                try {
                    // ========== 核心修改：使用MP自带的saveBatch批量插入 ==========
                    boolean isSuccess = usernamePhoneService.saveBatch(batchList, BATCH_SIZE);
                    if (isSuccess) {
                        successCount.addAndGet(batchList.size());
                        System.out.println("批次[" + start + "-" + end + "]写入成功，数量：" + batchList.size());
                    } else {
                        failCount.addAndGet(batchList.size());
                        System.err.println("批次[" + start + "-" + end + "]写入失败：MP批量插入返回false");
                    }
                } catch (Exception e) {
                    failCount.addAndGet(batchList.size());
                    System.err.println("批次[" + start + "-" + end + "]写入失败：" + e.getMessage());
                    e.printStackTrace();
                } finally {
                    countDownLatch.countDown();
                }
            });
        }

        // 5. 等待所有批次完成
        System.out.println("等待所有写入批次完成...");
        countDownLatch.await(300, TimeUnit.SECONDS);
        executorService.shutdown();

        // 6. 结果统计
        System.out.println("==================== 同步结果 ====================");
        System.out.println("原始user数据总量：" + allUserList.size());
        System.out.println("去重后有效数据量：" + totalValidCount);
        System.out.println("成功写入路由表数量：" + successCount.get());
        System.out.println("写入失败数量：" + failCount.get());
        System.out.println("==================================================");
    }
}