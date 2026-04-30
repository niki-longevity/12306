package com.project.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.project.common.pojo.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    // 新增：查询所有已存在的用户名
    @Select("SELECT username FROM user WHERE username IS NOT NULL") // 表名/字段名根据实际调整
    List<String> selectAllUsernames();
}
