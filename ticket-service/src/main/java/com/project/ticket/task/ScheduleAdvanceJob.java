package com.project.ticket.task;

import com.project.ticket.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleAdvanceJob {

    private final ScheduleService scheduleService;

    @Scheduled(cron = "0 0 2 * * ?")
    public void advanceWindow() {
        log.info("Starting daily schedule advance...");
        try {
            scheduleService.advanceWindow();
            log.info("Daily schedule advance completed");
        } catch (Exception e) {
            log.error("Schedule advance failed", e);
        }
    }
}
