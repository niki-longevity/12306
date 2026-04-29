package com.project.warmUp;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.project.mapper.UserMapper;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@SpringBootTest
public class BloomFilterInit {

    // 注入用户名布隆过滤器
    @Autowired
    private RBloomFilter<String> usernameBloomFilter;

    // 注入用户Mapper，用于查询现有用户名
    @Autowired
    private UserMapper userMapper;

    // 线程池核心参数：CPU核心数*2（兼顾效率和资源占用）
    private static final int CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;
    private static final int MAX_POOL_SIZE = CORE_POOL_SIZE * 2;
    private static final long KEEP_ALIVE_TIME = 60L;

    /**
     * 并发初始化布隆过滤器：加载所有已存在的用户名
     */
    @Test
    public void initUsernameBloomFilter() throws InterruptedException, ExecutionException {
        // 1. 查询所有分库分表中的用户名（ShardingSphere自动广播）
        List<String> existUsernames = userMapper.selectAllUsernames();

        if (existUsernames == null || existUsernames.isEmpty()) {
            System.out.println("数据库中暂无用户名，布隆过滤器初始化完成（空）");
            return;
        }

        // 2. 数据预处理：去重+过滤空值（避免无效数据）
        List<String> validUsernames = existUsernames.stream()
                .filter(username -> username != null && !username.trim().isEmpty())
                .distinct()
                .collect(Collectors.toList());

        int totalCount = validUsernames.size();
        System.out.println("待加载有效用户名总数：" + totalCount);

        // 3. 初始化线程池（核心：适配CPU核心数，避免线程过多导致上下文切换）
        ExecutorService executorService = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10000), // 任务队列容量
                new ThreadFactory() { // 自定义线程名，方便日志排查
                    private int threadNum = 1;
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "bloom-filter-init-thread-" + threadNum++);
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时，由提交任务的线程执行（避免任务丢失）
        );

        // 4. 线程安全计数器：统计已加载数量
        AtomicInteger loadedCount = new AtomicInteger(0);

        // 5. 分片处理：将列表分成N份（N=核心线程数），并发添加
        int batchSize = Math.max(totalCount / CORE_POOL_SIZE, 1000); // 每个线程至少处理1000条
        for (int i = 0; i < totalCount; i += batchSize) {
            int start = i;
            int end = Math.min(i + batchSize, totalCount);
            List<String> batchList = validUsernames.subList(start, end);

            // 提交并发任务
            executorService.submit(() -> {
                for (String username : batchList) {
                    usernameBloomFilter.add(username);
                    // 每加载1000条打印一次进度（减少日志IO）
                    int current = loadedCount.incrementAndGet();
                    if (current % 1000 == 0) {
                        System.out.println("已并发加载 " + current + "/" + totalCount + " 个用户名");
                    }
                }
            });
        }

        // 6. 等待所有任务完成 + 关闭线程池
        executorService.shutdown();
        // 等待超时时间：根据数据量调整（50万数据建议300秒）
        if (!executorService.awaitTermination(300, TimeUnit.SECONDS)) {
            // 超时后强制关闭（避免线程泄漏）
            executorService.shutdownNow();
            System.err.println("布隆过滤器初始化超时，部分任务未完成！");
        }

        // 7. 最终结果打印
        System.out.println("布隆过滤器并发初始化完成！");
        System.out.println("原始查询数量：" + existUsernames.size());
        System.out.println("去重后有效数量：" + totalCount);
        System.out.println("实际加载数量：" + loadedCount.get());
    }
}