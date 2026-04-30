package com.project.config;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RocketMQConfig {

    @Bean
    public RocketMQTemplate rocketMQTemplate(DefaultMQProducer defaultMQProducer) {
        RocketMQTemplate template = new RocketMQTemplate();
        template.setProducer(defaultMQProducer);
        return template;
    }
}
