package com.project.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.project.common.pojo.entity.SeatBitmap;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeatBitmapMapper extends BaseMapper<SeatBitmap> {

    @Update("UPDATE seat_bitmap SET bitmap = bitmap | #{mask}, version = version + 1 " +
            "WHERE train_code = #{trainCode} AND date = #{date} AND seat_type = #{seatType} " +
            "AND carriage_num = #{carriageNum} AND seat_num = #{seatNum} " +
            "AND (bitmap & #{mask}) = 0")
    int updateBitmapIfNoConflict(@Param("trainCode") String trainCode,
                                 @Param("date") java.time.LocalDate date,
                                 @Param("seatType") int seatType,
                                 @Param("carriageNum") int carriageNum,
                                 @Param("seatNum") int seatNum,
                                 @Param("mask") byte[] mask);

    @Update("UPDATE seat_bitmap SET bitmap = bitmap & ~#{mask}, version = version + 1 " +
            "WHERE train_code = #{trainCode} AND date = #{date} AND seat_type = #{seatType} " +
            "AND carriage_num = #{carriageNum} AND seat_num = #{seatNum}")
    int clearBitmap(@Param("trainCode") String trainCode,
                    @Param("date") java.time.LocalDate date,
                    @Param("seatType") int seatType,
                    @Param("carriageNum") int carriageNum,
                    @Param("seatNum") int seatNum,
                    @Param("mask") byte[] mask);
}
