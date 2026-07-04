-- 座位位图表 (MySQL 真相源)
CREATE TABLE IF NOT EXISTS seat_bitmap (
    id            BIGINT PRIMARY KEY COMMENT '雪花ID',
    train_code    VARCHAR(20) NOT NULL,
    date          DATE NOT NULL,
    seat_type     INT NOT NULL COMMENT '0商务/1一等/2二等',
    carriage_num  INT NOT NULL,
    seat_num      INT NOT NULL,
    bitmap        VARBINARY(256) NOT NULL DEFAULT '' COMMENT '每个section 1 bit, 1=已售',
    version       INT NOT NULL DEFAULT 0,
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_seat (train_code, date, seat_type, carriage_num, seat_num)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 本地消息表
CREATE TABLE IF NOT EXISTS ticket_outbox (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_type  VARCHAR(32) NOT NULL COMMENT 'ORDER_CREATE / ORDER_CLOSE',
    payload       TEXT NOT NULL COMMENT 'JSON',
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/DEAD',
    retry_count   INT NOT NULL DEFAULT 0,
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_retry    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
