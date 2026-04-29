package com.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.project.pojo.entity.TrainCarriage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TrainCarriageMapper extends BaseMapper<TrainCarriage> {

    /**
     * 批量插入车次车厢数据
     */
    @Insert("<script>" +
            "INSERT INTO train_carriage (train_code, business_carriage, first_class_carriage, second_class_carriage) " +
            "VALUES " +
            "<foreach collection='list' item='item' separator=','> " +
            "(#{item.trainCode}, #{item.businessCarriage}, #{item.firstClassCarriage}, #{item.secondClassCarriage}) " +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("list") List<TrainCarriage> list);
}
