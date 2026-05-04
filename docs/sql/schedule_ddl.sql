CREATE TABLE IF NOT EXISTS station_dict (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    station_name VARCHAR(50) NOT NULL COMMENT '站名',
    city VARCHAR(30) NOT NULL COMMENT '地级市',
    province VARCHAR(20) NOT NULL COMMENT '省份',
    pinyin VARCHAR(100) NOT NULL COMMENT '全拼',
    pinyin_abbr VARCHAR(30) NOT NULL COMMENT '拼音首字母',
    sort_order INT DEFAULT 0,
    UNIQUE KEY uk_name (station_name),
    INDEX idx_city (city),
    INDEX idx_pinyin_abbr (pinyin_abbr)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS train_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    train_code VARCHAR(20) NOT NULL COMMENT '车次编号',
    line_code VARCHAR(20) COMMENT '干线编号',
    business_carriage INT DEFAULT 1,
    first_class_carriage INT DEFAULT 1,
    second_class_carriage INT DEFAULT 6,
    UNIQUE KEY uk_code (train_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS train_template_stopover (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    train_code VARCHAR(20) NOT NULL,
    station_name VARCHAR(50) NOT NULL,
    station_index INT NOT NULL COMMENT '站序 1-based',
    in_time TIME,
    out_time TIME,
    mileage INT DEFAULT 0 COMMENT '累计里程km',
    INDEX idx_train_code (train_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS schedule_window (
    id INT PRIMARY KEY DEFAULT 1,
    window_start DATE NOT NULL COMMENT '可售票起始日',
    window_end DATE NOT NULL COMMENT '可售票结束日',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
