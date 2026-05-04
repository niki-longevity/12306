package com.project.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ticket.mapper.*;
import com.project.ticket.pojo.entity.*;
import com.project.ticket.service.impl.ScheduleServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.*;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock private ScheduleWindowMapper windowMapper;
    @Mock private TrainTemplateMapper templateMapper;
    @Mock private TrainTemplateStopoverMapper stopoverMapper;
    @Mock private TrainStopoverMapper trainStopoverMapper;
    @Mock private StringRedisTemplate redis;
    @Mock private ObjectMapper objectMapper;

    private ScheduleService service;

    @BeforeEach
    void setUp() {
        service = new ScheduleServiceImpl(windowMapper, templateMapper, stopoverMapper,
                trainStopoverMapper, redis, objectMapper);
    }

    @Test
    void advanceWindow_shouldMoveWindowByOneDay() {
        ScheduleWindow window = ScheduleWindow.builder().id(1)
                .windowStart(LocalDate.of(2026, 5, 5))
                .windowEnd(LocalDate.of(2026, 5, 19)).build();
        when(windowMapper.selectById(1)).thenReturn(window);
        when(templateMapper.selectList(any())).thenReturn(List.of());

        service.advanceWindow();

        verify(windowMapper).updateById(Mockito.<ScheduleWindow>argThat(w ->
                w.getWindowStart().equals(LocalDate.of(2026, 5, 6)) &&
                w.getWindowEnd().equals(LocalDate.of(2026, 5, 20))));
    }

    @Test
    void isDateInWindow_shouldRejectOutOfRange() {
        when(windowMapper.selectById(1)).thenReturn(ScheduleWindow.builder()
                .windowStart(LocalDate.of(2026, 5, 6))
                .windowEnd(LocalDate.of(2026, 5, 20)).build());

        assertThat(service.isDateInWindow(LocalDate.of(2026, 5, 10))).isTrue();
        assertThat(service.isDateInWindow(LocalDate.of(2026, 5, 1))).isFalse();
        assertThat(service.isDateInWindow(LocalDate.of(2026, 5, 25))).isFalse();
    }
}
