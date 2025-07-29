# 图书收集器 (Book Collector)

一个用于从微信读书API获取图书信息并存储到MySQL数据库的Python工具。

## 功能特性

- 🔍 从微信读书API获取图书信息
- 💾 将图书数据存储到MySQL数据库
- 📊 支持多种分类的图书收集
- 📈 提供数据库统计和查询功能
- 🔄 支持增量更新和重复数据处理
- 📝 详细的日志记录

## 项目结构

```
book_collector/
├── database_schema.sql      # 数据库建表SQL
├── book_api_client.py       # API请求客户端
├── book_database.py         # 数据库操作类
├── book_collector.py        # 主程序
├── requirements.txt         # Python依赖
├── README.md               # 项目说明
└── books.json             # 示例API响应数据
```

## 安装和配置

### 1. 安装Python依赖

```bash
pip install -r requirements.txt
```

### 2. 创建数据库

在MySQL服务器上运行 `database_schema.sql` 文件：

```bash
mysql -u your_username -p < database_schema.sql
```

### 3. 配置数据库连接

修改 `config.py` 中的数据库配置：

```python
DATABASE_CONFIG = {
    'host': '<HOST>', # 请替换为实际的HOST
    'port': 3306,
    'user': '<USER>',      # 请替换为实际的用户名
    'password': '<PASSWORD>',  # 请替换为实际的密码
    'database': '<DATABASE>' # 请替换为实际的数据库名
}
```

## 使用方法

### 基本使用

```python
from book_collector import BookCollector

# 创建收集器实例
collector = BookCollector(db_config)

# 收集单个分类的图书
books = collector.collect_books_by_category(300000, max_pages=10)

# 收集多个分类的图书
results = collector.collect_multiple_categories([300000, 100000], max_pages_per_category=5)

# 收集榜单图书
books = collector.collect_ranking_books('rising', max_pages=10)

# 收集多个榜单的图书
results = collector.collect_multiple_rankings(['rising', 'hot_search'], max_pages_per_ranking=5)

### 运行主程序

```bash
python book_collector.py
```

### 命令行工具使用

#### 基本收集
```bash
# 显示可用分类
python run.py --categories

# 收集指定分类的图书
python run.py --collect 300000 100000 --pages 5

# 收集所有分类的图书
python run.py --all --pages 3

# 从指定索引开始收集
python run.py --from-index 300000 1000 --pages 3

# 继续上次未完成的收集
python run.py --continue-collection 300000 --pages 5

# 显示指定分类的最后请求索引
python run.py --last-index 300000

# 显示统计信息
python run.py --stats

# 显示高分图书
python run.py --top 10

# 测试API连接
python run.py --test
```

#### 榜单收集
```bash
# 显示可用榜单
python run.py --rankings

# 收集指定榜单的图书
python run.py --ranking rising hot_search --pages 5

# 收集所有榜单的图书
python run.py --all-rankings --pages 3
```

#### 大量数据处理策略

**场景：收集所有分类的图书**
```bash
# 收集所有分类的图书（每个分类5页）
python run.py --all --pages 5

# 收集所有分类的图书（每个分类10页）
python run.py --all --pages 10
```

**注意事项：**
- 这将收集 `config.py` 中定义的所有分类
- 总共会处理 21 个分类
- 建议先用较小的页数测试，确认正常后再增加页数
- 整个过程可能需要较长时间，请耐心等待

**场景：每个分类有几万本书**

策略1：分批收集
```bash
# 第一次收集（前1000本）
python run.py --collect 300000 --pages 20

# 继续收集（从上次停止的地方）
python run.py --continue-collection 300000 --pages 20

# 重复执行直到收集完成
```

策略2：指定索引收集
```bash
# 查看当前进度
python run.py --last-index 300000

# 从指定位置继续
python run.py --from-index 300000 5000 --pages 20
```

策略3：编程方式批量处理
```python
import time
from book_collector import BookCollector
from config import DATABASE_CONFIG

collector = BookCollector(DATABASE_CONFIG)
category_id = 300000

while True:
    # 继续收集
    result = collector.continue_collection(category_id, max_pages=10)
    
    print(f"本次收集: {result['book_count']} 本")
    print(f"最后索引: {result['last_index']}")
    
    # 如果没有更多数据，退出
    if result['book_count'] == 0:
        print("收集完成！")
        break
    
    # 等待一段时间再继续
    time.sleep(5)
```

### 获取统计信息

```python
# 获取数据库统计
stats = collector.get_database_statistics()
print(f"总图书数量: {stats['total_books']}")

# 获取高分图书
top_books = collector.get_top_books(10)
for book in top_books:
    print(f"{book['title']} - 评分: {book['new_rating']}")

# 从指定索引开始收集
result = collector.collect_books_from_index(300000, 1000, max_pages=5)

# 继续上次未完成的收集
continue_result = collector.continue_collection(300000, max_pages=3)

# 获取最后请求索引
last_index = collector.get_last_request_index(300000)
```

## 常用分类ID

| 分类ID | 分类名称 | 预估图书数量 |
|--------|----------|-------------|
| 300000 | 文学 | 4万+ |
| 100000 | 精品小说 | 3万+ |
| 200000 | 历史 | 2万+ |
| 400000 | 艺术 | 1.5万+ |
| 500000 | 人物传记 | 1万+ |

## 索引说明

- **maxIndex**: API分页参数，表示从第几本书开始获取
- **search_idx**: 每本书的唯一索引，用于下次请求
- **自动递增**: 程序会自动使用最后一本书的search_idx作为下次请求的maxIndex

## 最佳实践

### 1. 首次收集
```bash
# 先收集少量数据测试
python run.py --collect 300000 --pages 5

# 确认正常后，开始大量收集
python run.py --collect 300000 --pages 50
```

### 2. 断点续传
```bash
# 查看进度
python run.py --last-index 300000

# 继续收集
python run.py --continue-collection 300000 --pages 50
```

### 3. 监控进度
```bash
# 查看统计信息
python run.py --stats

# 查看高分图书
python run.py --top 10
```

### 4. 错误恢复
```bash
# 如果程序中断，查看最后索引
python run.py --last-index 300000

# 从该索引继续
python run.py --from-index 300000 [最后索引] --pages 20
```

## 数据库表结构

### 主要表

1. **books** - 图书基本信息
2. **book_categories** - 图书分类关联
3. **book_rating_details** - 评分详情
4. **api_requests** - API请求记录

### 常用分类ID

- `300000` - 文学
- `100000` - 精品小说
- `200000` - 经管励志
- `400000` - 人文社科
- `500000` - 生活艺术

### 榜单类型

- `rising` - 飙升榜
- `hot_search` - 热搜榜
- `newbook` - 新书榜
- `general_novel_rising` - 小说飙升榜
- `all` - 总榜
- `newrating_publish` - 新评分出版榜
- `newrating_potential_publish` - 新评分潜力出版榜

## API说明

### 请求地址

```
https://weread.qq.com/web/bookListInCategory/{category_id}?maxIndex={max_index}
```

### 参数说明

- `category_id`: 分类ID，用于获取不同分类的图书
- `max_index`: 分页索引，用于分页获取数据

### 返回数据

API返回JSON格式的图书列表，包含图书的详细信息如标题、作者、评分、价格等。

## 注意事项

1. **请求频率**: 程序已内置1秒延迟，避免请求过于频繁
2. **数据更新**: 程序会自动处理重复数据，已存在的图书会更新信息
3. **错误处理**: 程序包含完善的错误处理和日志记录
4. **数据库连接**: 使用上下文管理器确保数据库连接正确关闭
5. **数据完整性**: 程序会自动去重，重复运行安全
6. **网络稳定**: 确保网络连接稳定，程序会自动重试

## 故障排除

### 常见问题

1. **网络错误**: 检查网络连接，程序会自动重试
2. **数据库连接失败**: 检查数据库配置和连接信息
3. **索引错误**: 使用 `--last-index` 查看当前进度
4. **数据重复**: 程序会自动处理，无需担心
5. **datetime字段错误**: 程序已修复空字符串导致的datetime错误


### 调试命令
```bash
# 测试API连接
python run.py --test

# 查看数据库统计
python run.py --stats

# 查看日志文件
tail -f book_collector.log
```

## 日志文件

程序运行时会生成 `book_collector.log` 日志文件，记录详细的执行信息。

## 扩展功能

可以根据需要扩展以下功能：

- 图书搜索功能
- 数据导出功能
- Web界面展示
- 定时任务调度
- 数据分析和可视化

## 许可证

本项目仅供学习和研究使用，请遵守相关网站的使用条款。

## 贡献

欢迎提交Issue和Pull Request来改进这个项目。 