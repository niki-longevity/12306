package com.project.user.config;

import org.redisson.Redisson;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RBloomFilterConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    // Redis密码
    @Value("${spring.data.redis.password:}")
    private String password;


    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 修正地址格式，确保加前缀
        config.useSingleServer()
                .setPassword(password)
                .setAddress("redis://" + host + ":6379");
        return Redisson.create(config);
    }

    @Bean
    public RBloomFilter<String> usernameBloomFilter(RedissonClient redissonClient) {
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter("bloom:username");
        // 初始化参数（根据实际车站数量调整）
        bloomFilter.tryInit(600000L, 0.1);
        return bloomFilter;
    }
}
