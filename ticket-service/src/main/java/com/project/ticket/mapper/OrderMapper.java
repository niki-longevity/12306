package com.project.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.project.common.pojo.entity.Order;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
