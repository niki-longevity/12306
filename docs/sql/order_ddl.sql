CREATE TABLE IF NOT EXISTS orders (
    id BIGINT PRIMARY KEY COMMENT '订单ID（雪花算法）',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    date DATE NOT NULL COMMENT '乘车日期',
    train_code VARCHAR(20) NOT NULL COMMENT '车次编号',
    start_station VARCHAR(50) NOT NULL COMMENT '出发站',
    end_station VARCHAR(50) NOT NULL COMMENT '到达站',
    seat_type INT NOT NULL COMMENT '座位类型',
    carriage_num INT NOT NULL COMMENT '车厢号',
    seat_num INT NOT NULL COMMENT '座位号',
    status VARCHAR(16) NOT NULL DEFAULT 'UNPAID' COMMENT '订单状态',
    expire_time DATETIME NOT NULL COMMENT '过期关单时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    start_section INT NOT NULL DEFAULT 0,
    end_section INT NOT NULL DEFAULT 0,
    total_section_count INT NOT NULL DEFAULT 0,
    passenger_count INT NOT NULL DEFAULT 0,
    sections_json VARCHAR(1024) DEFAULT '',
    seat_start_bit BIGINT NOT NULL DEFAULT 0,
    INDEX idx_user_id (user_id),
    INDEX idx_status_expire (status, expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

CREATE TABLE IF NOT EXISTS order_passengers (
    id BIGINT PRIMARY KEY COMMENT 'ID（雪花算法）',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    real_name VARCHAR(50) NOT NULL COMMENT '乘车人姓名',
    id_card VARCHAR(20) NOT NULL COMMENT '乘车人身份证号',
    INDEX idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单乘车人表';
