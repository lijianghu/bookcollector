-- 创建数据库
CREATE DATABASE IF NOT EXISTS book_collector CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 使用数据库
USE book_collector;

-- 创建图书信息表
CREATE TABLE IF NOT EXISTS books (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    book_id VARCHAR(50) NOT NULL UNIQUE COMMENT '图书ID',
    title VARCHAR(500) NOT NULL COMMENT '图书标题',
    author VARCHAR(200) COMMENT '作者',
    translator VARCHAR(200) COMMENT '译者',
    cover VARCHAR(1000) COMMENT '封面图片URL',
    version BIGINT COMMENT '版本号',
    format VARCHAR(50) COMMENT '格式',
    type INT COMMENT '类型',
    price DECIMAL(10,2) COMMENT '价格',
    original_price DECIMAL(10,2) COMMENT '原价',
    soldout TINYINT COMMENT '是否售罄',
    book_status INT COMMENT '图书状态',
    paying_status INT COMMENT '付费状态',
    pay_type BIGINT COMMENT '付费类型',
    intro TEXT COMMENT '图书简介',
    cent_price INT COMMENT '价格（分）',
    finished TINYINT COMMENT '是否完结',
    max_free_chapter INT COMMENT '最大免费章节',
    free TINYINT COMMENT '是否免费',
    mcard_discount TINYINT COMMENT '会员卡折扣',
    ispub TINYINT COMMENT '是否出版',
    extra_type INT COMMENT '额外类型',
    cpid BIGINT COMMENT 'CP ID',
    publish_time DATETIME NULL COMMENT '出版时间',
    category VARCHAR(200) COMMENT '分类',
    has_lecture TINYINT COMMENT '是否有讲座',
    last_chapter_idx INT COMMENT '最后章节索引',
    paper_book_sku_id VARCHAR(100) COMMENT '纸质书SKU ID',
    block_save_img TINYINT COMMENT '是否阻止保存图片',
    language VARCHAR(50) COMMENT '语言',
    is_traditional_chinese TINYINT COMMENT '是否繁体中文',
    hide_update_time TINYINT COMMENT '是否隐藏更新时间',
    is_epub_comics TINYINT COMMENT '是否EPUB漫画',
    is_vertical_layout TINYINT COMMENT '是否垂直布局',
    is_show_tts TINYINT COMMENT '是否显示TTS',
    web_book_control TINYINT COMMENT '网页图书控制',
    self_produce_incentive TINYINT COMMENT '自制激励',
    is_auto_download TINYINT COMMENT '是否自动下载',
    new_rating DECIMAL(5,2) COMMENT '新评分',
    new_rating_count INT COMMENT '新评分数量',
    new_rating_title VARCHAR(100) COMMENT '新评分标题',
    search_idx INT COMMENT '搜索索引',
    type_info INT COMMENT '类型信息',
    reading_count INT COMMENT '阅读数量',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_book_id (book_id),
    INDEX idx_title (title),
    INDEX idx_author (author),
    INDEX idx_category (category),
    INDEX idx_publish_time (publish_time),
    INDEX idx_new_rating (new_rating),
    INDEX idx_reading_count (reading_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图书信息表';

-- 创建分类信息表
CREATE TABLE IF NOT EXISTS book_categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    book_id VARCHAR(50) NOT NULL COMMENT '图书ID',
    category_id INT COMMENT '分类ID',
    sub_category_id INT COMMENT '子分类ID',
    category_type INT COMMENT '分类类型',
    title VARCHAR(200) COMMENT '分类标题',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE,
    INDEX idx_book_id (book_id),
    INDEX idx_category_id (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图书分类关联表';

-- 创建评分详情表
CREATE TABLE IF NOT EXISTS book_rating_details (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    book_id VARCHAR(50) NOT NULL COMMENT '图书ID',
    good_count INT COMMENT '好评数量',
    fair_count INT COMMENT '一般数量',
    poor_count INT COMMENT '差评数量',
    recent_count INT COMMENT '最近评价数量',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE,
    INDEX idx_book_id (book_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图书评分详情表';

-- 创建API请求记录表
CREATE TABLE IF NOT EXISTS api_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id INT NOT NULL COMMENT '分类ID',
    max_index INT NOT NULL COMMENT '最大索引',
    request_url VARCHAR(1000) NOT NULL COMMENT '请求URL',
    response_synckey BIGINT COMMENT '响应同步键',
    total_count INT COMMENT '总数量',
    has_more TINYINT COMMENT '是否有更多',
    books_count INT COMMENT '图书数量',
    status_code INT COMMENT 'HTTP状态码',
    response_time_ms INT COMMENT '响应时间(毫秒)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_category_id (category_id),
    INDEX idx_max_index (max_index),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API请求记录表';