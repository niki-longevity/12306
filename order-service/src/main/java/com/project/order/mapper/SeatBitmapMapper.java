package com.project.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.project.common.pojo.entity.SeatBitmap;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeatBitmapMapper extends BaseMapper<SeatBitmap> {

    @Insert("INSERT INTO seat_bitmap (id, train_code, date, seat_type, carriage_num, seat_num, bitmap, version, create_time, update_time) " +
            "VALUES (#{id}, #{trainCode}, #{date}, #{seatType}, #{carriageNum}, #{seatNum}, #{mask}, 1, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE " +
            "  bitmap = IF((bitmap & #{mask}) = 0, bitmap | #{mask}, bitmap), " +
            "  version = version + IF((bitmap & #{mask}) = 0, 1, 0), " +
            "  update_time = IF((bitmap & #{mask}) = 0, NOW(), update_time)")
    int upsertBitmap(@Param("id") long id,
                     @Param("trainCode") String trainCode,
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
