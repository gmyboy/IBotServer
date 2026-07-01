-- 用户与设备绑定表（已有库执行一次）

CREATE TABLE IF NOT EXISTS pb_core_users (
    user_id VARCHAR(255) NOT NULL PRIMARY KEY,
    display_name VARCHAR(255) NULL,
    created_at VARCHAR(255) NULL,
    updated_at VARCHAR(255) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pb_core_devices (
    device_id VARCHAR(255) NOT NULL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    robot_id VARCHAR(255) NOT NULL,
    device_name VARCHAR(255) NULL,
    bound_at VARCHAR(255) NULL,
    last_seen_at VARCHAR(255) NULL,
    INDEX pb_dev_idx_user (user_id),
    INDEX pb_dev_idx_robot (robot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
