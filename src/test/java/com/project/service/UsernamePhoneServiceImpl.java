package com.project.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.project.mapper.UsernamePhoneMapper;
import com.project.pojo.entity.UsernamePhone;
import org.springframework.stereotype.Service;

/**
 * Service实现类，继承ServiceImpl（绑定Mapper和实体类）
 */
@Service
public class UsernamePhoneServiceImpl extends ServiceImpl<UsernamePhoneMapper, UsernamePhone> 
        implements UsernamePhoneService {
    // 无需写任何代码，自动继承IService的所有方法（saveBatch/removeBatch等）
}