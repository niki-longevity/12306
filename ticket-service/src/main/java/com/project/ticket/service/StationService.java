package com.project.ticket.service;

import com.project.ticket.pojo.vo.CityIndexVO;
import com.project.ticket.pojo.vo.StationVO;
import java.util.List;

public interface StationService {
    List<StationVO> search(String keyword);
    List<CityIndexVO> cityIndex();
}
