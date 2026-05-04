package com.project.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.project.ticket.mapper.StationDictMapper;
import com.project.ticket.pojo.entity.StationDict;
import com.project.ticket.pojo.vo.CityIndexVO;
import com.project.ticket.pojo.vo.StationVO;
import com.project.ticket.service.impl.StationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationServiceTest {

    @Mock
    private StationDictMapper mapper;

    private StationService service;

    @BeforeEach
    void setUp() {
        service = new StationServiceImpl(mapper);
    }

    @Test
    void searchByCity_shouldReturnAllStationsInCity() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                StationDict.builder().stationName("广州南站").city("广州").province("广东")
                        .pinyin("guangzhounan").pinyinAbbr("gzn").build(),
                StationDict.builder().stationName("广州东站").city("广州").province("广东")
                        .pinyin("guangzhoudong").pinyinAbbr("gzd").build()
        ));

        List<StationVO> result = service.search("广州");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStationName()).isEqualTo("广州南站");
        assertThat(result.get(1).getStationName()).isEqualTo("广州东站");
    }

    @Test
    void searchByPinyinAbbr_shouldMatchPrefix() {
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.emptyList())
                .thenReturn(List.of(
                        StationDict.builder().stationName("深圳北站").city("深圳").province("广东")
                                .pinyin("shenzhenbei").pinyinAbbr("szb").build()
                ));

        List<StationVO> result = service.search("szb");

        assertThat(result).hasSize(1);
    }

    @Test
    void cityIndex_shouldGroupByFirstLetter() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                StationDict.builder().city("长沙").pinyin("changsha").build(),
                StationDict.builder().city("郴州").pinyin("chenzhou").build(),
                StationDict.builder().city("广州").pinyin("guangzhou").build()
        ));

        List<CityIndexVO> result = service.cityIndex();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getLetter()).isEqualTo("C");
        assertThat(result.get(0).getCities()).containsExactly("郴州", "长沙");
        assertThat(result.get(1).getLetter()).isEqualTo("G");
    }
}
