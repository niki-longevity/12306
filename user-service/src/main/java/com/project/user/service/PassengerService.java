package com.project.user.service;

import com.project.common.pojo.dto.PassengerSaveDTO;
import com.project.common.pojo.entity.Passenger;

import java.util.List;

public interface PassengerService {
    List<Passenger> list(Long userId);
    Passenger add(Long userId, PassengerSaveDTO dto);
    Passenger update(Long passengerId, Long userId, PassengerSaveDTO dto);
    void delete(Long passengerId, Long userId);
}
