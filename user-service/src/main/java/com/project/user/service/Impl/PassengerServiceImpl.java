package com.project.user.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.project.common.exception.BaseException;
import com.project.common.pojo.dto.PassengerSaveDTO;
import com.project.common.pojo.entity.Passenger;
import com.project.user.mapper.PassengerMapper;
import com.project.user.service.PassengerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PassengerServiceImpl implements PassengerService {

    private final PassengerMapper passengerMapper;

    @Override
    public List<Passenger> list(Long userId) {
        LambdaQueryWrapper<Passenger> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Passenger::getUserId, userId)
               .orderByDesc(Passenger::getCreateTime);
        return passengerMapper.selectList(wrapper);
    }

    @Override
    public Passenger add(Long userId, PassengerSaveDTO dto) {
        long count = passengerMapper.selectCount(
                new LambdaQueryWrapper<Passenger>().eq(Passenger::getUserId, userId));
        if (count >= 10) {
            throw new BaseException("最多添加10位乘车人");
        }

        Passenger passenger = new Passenger();
        passenger.setUserId(userId);
        passenger.setRealName(dto.getRealName());
        passenger.setIdCard(dto.getIdCard());
        passenger.setPassengerType(dto.getPassengerType() != null ? dto.getPassengerType() : "ADULT");
        passenger.setCreateTime(LocalDateTime.now());
        passengerMapper.insert(passenger);
        log.info("添加乘车人：id={}, name={}", passenger.getId(), passenger.getRealName());
        return passenger;
    }

    @Override
    public Passenger update(Long passengerId, Long userId, PassengerSaveDTO dto) {
        Passenger passenger = passengerMapper.selectById(passengerId);
        if (passenger == null || !passenger.getUserId().equals(userId)) {
            throw new BaseException("乘车人不存在");
        }
        passenger.setRealName(dto.getRealName());
        passenger.setIdCard(dto.getIdCard());
        passenger.setPassengerType(dto.getPassengerType());
        passengerMapper.updateById(passenger);
        return passenger;
    }

    @Override
    public void delete(Long passengerId, Long userId) {
        Passenger passenger = passengerMapper.selectById(passengerId);
        if (passenger == null || !passenger.getUserId().equals(userId)) {
            throw new BaseException("乘车人不存在");
        }
        passengerMapper.deleteById(passengerId);
        log.info("删除乘车人：id={}", passengerId);
    }
}
