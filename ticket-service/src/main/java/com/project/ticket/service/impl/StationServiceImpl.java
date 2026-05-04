package com.project.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.project.ticket.mapper.StationDictMapper;
import com.project.ticket.pojo.entity.StationDict;
import com.project.ticket.pojo.vo.CityIndexVO;
import com.project.ticket.pojo.vo.StationVO;
import com.project.ticket.service.StationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StationServiceImpl implements StationService {

    private final StationDictMapper mapper;

    @Override
    public List<StationVO> search(String keyword) {
        if (!StringUtils.hasText(keyword) || keyword.length() < 1) {
            return Collections.emptyList();
        }

        String kw = keyword.trim();
        List<StationDict> byCity = mapper.selectList(new QueryWrapper<StationDict>()
                .eq("city", kw));
        if (!byCity.isEmpty()) {
            return byCity.stream()
                    .sorted(Comparator.comparing(StationDict::getSortOrder,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(this::toVO)
                    .limit(20)
                    .toList();
        }

        List<StationDict> results = mapper.selectList(new QueryWrapper<StationDict>()
                .like("station_name", kw)
                .or().like("pinyin", kw.toLowerCase())
                .or().like("pinyin_abbr", kw.toLowerCase())
                .last("LIMIT 20"));
        return results.stream()
                .sorted(Comparator.comparing(StationDict::getSortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toVO)
                .toList();
    }

    @Override
    public List<CityIndexVO> cityIndex() {
        List<StationDict> all = mapper.selectList(new QueryWrapper<StationDict>()
                .select("city", "pinyin")
                .groupBy("city"));

        Map<String, String> cityFirstLetter = new HashMap<>();
        for (StationDict s : all) {
            String pinyin = s.getPinyin();
            if (pinyin != null && !pinyin.isEmpty()) {
                cityFirstLetter.put(s.getCity(), pinyin.substring(0, 1).toUpperCase());
            }
        }

        Map<String, Set<String>> grouped = new TreeMap<>();
        for (StationDict s : all) {
            String letter = cityFirstLetter.getOrDefault(s.getCity(), "#");
            grouped.computeIfAbsent(letter, k -> new TreeSet<>()).add(s.getCity());
        }

        return grouped.entrySet().stream()
                .map(e -> CityIndexVO.builder().letter(e.getKey()).cities(new ArrayList<>(e.getValue())).build())
                .toList();
    }

    private StationVO toVO(StationDict entity) {
        return StationVO.builder()
                .stationName(entity.getStationName())
                .city(entity.getCity())
                .province(entity.getProvince())
                .pinyinAbbr(entity.getPinyinAbbr())
                .build();
    }
}
