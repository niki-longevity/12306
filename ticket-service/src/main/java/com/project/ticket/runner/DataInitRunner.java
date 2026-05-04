package com.project.ticket.runner;

import com.project.ticket.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitRunner implements ApplicationRunner {

    private final ScheduleService scheduleService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Checking data initialization...");
        try {
            scheduleService.initialize15Days(LocalDate.now());
            log.info("Data initialization check completed");
        } catch (Exception e) {
            log.error("Data initialization failed", e);
        }
    }
}
