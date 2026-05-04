package com.project.ticket.service;

import java.time.LocalDate;

public interface ScheduleService {
    void advanceWindow();
    void initialize15Days(LocalDate fromDate);
    boolean isDateInWindow(LocalDate date);
    LocalDate getWindowStart();
    LocalDate getWindowEnd();
}
