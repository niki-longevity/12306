package com.project.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.project.ticket.pojo.entity.TrainStopover;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TrainStopoverMapper extends BaseMapper<TrainStopover> {

    int insertBatch(@Param("list") List<TrainStopover> list);
}
