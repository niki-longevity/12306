package com.project.order.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 查询车次发车时间（从 ticket-service 生成的 train_stopover 表读取）
 */
@Mapper
public interface DepartureTimeMapper {

    @Select("SELECT out_time FROM train_stopover " +
            "WHERE date = #{date} AND code = #{code} AND stopover_station = #{station} LIMIT 1")
    LocalTime findDepartureTime(@Param("date") LocalDate date,
                                @Param("code") String code,
                                @Param("station") String station);
}
