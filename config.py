#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
配置文件
用于管理数据库连接和其他配置信息
"""

# MYSQL数据库配置
DATABASE_CONFIG = {
    'host': '<HOST>', # 请替换为实际的HOST
    'port': 3306,
    'user': '<USER>',      # 请替换为实际的用户名
    'password': '<PASSWORD>',  # 请替换为实际的密码
    'database': '<DATABASE>' # 请替换为实际的数据库名
}

# API配置
API_CONFIG = {
    'base_url': 'https://weread.qq.com/web/bookListInCategory',
    'timeout': 30,
    'delay_between_requests': 1,  # 请求间隔时间（秒）
    'max_retries': 3
}

# 常用分类ID
CATEGORIES = {
    300000: "文学",
    100000: "精品小说", 
    200000: "历史",
    400000: "艺术",
    500000: "人物传记",
    600000: "哲学宗教",
    700000: "计算机",
    800000: "心理",
    900000: "社会文化",
    1000000: "个人成长",
    1100000: "经济理财",
    1200000: "政治军事",
    1300000: "童书",
    1400000: "教育学习",
    1500000: "科学技术",
    1600000: "生活百科",
    1700000: "期刊杂志",
    1800000: "原版书",
    1900001: "男生小说",
    2000001: "女生小说",
    2100000: "医学健康"
}

# 日志配置
LOGGING_CONFIG = {
    'level': 'INFO',
    'format': '%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    'file': 'book_collector.log'
}

# 榜单类型
RANKING_LISTS = {
    'rising': '飙升榜',
    'hot_search': '热搜榜', 
    'newbook': '新书榜',
    'general_novel_rising': '小说飙升榜',
    'all': '总榜',
    'newrating_publish': '神作榜',
    'newrating_potential_publish': '潜力榜'
}

# 收集配置
COLLECTION_CONFIG = {
    'default_max_pages': 10,      # 默认最大页数
    'save_api_records': True,     # 是否保存API请求记录
    'batch_size': 100             # 批量处理大小
} 