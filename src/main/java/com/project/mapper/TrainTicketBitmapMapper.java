package com.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.project.pojo.entity.TrainTicketBitmap;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TrainTicketBitmapMapper extends BaseMapper<TrainTicketBitmap> {

    /**
     * 批量插入座位位图数据
     */
    @Insert("<script>" +
            "INSERT INTO train_ticket_bitmap (date, code, carriage_index, row_index, col_index, bitmap) " +
            "VALUES " +
            "<foreach collection='list' item='item' separator=','> " +
            "(#{item.date}, #{item.code}, #{item.carriageIndex}, #{item.rowIndex}, #{item.colIndex}, #{item.bitmap}) " +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("list") List<TrainTicketBitmap> list);
}
