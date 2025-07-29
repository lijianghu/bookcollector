import pymysql
import logging
from typing import List, Optional, Dict, Any
from datetime import datetime
from book_api_client import BookInfo, APIResponse


class BookDatabase:
    """图书数据库操作类"""
    
    def __init__(self, host: str, port: int, user: str, password: str, database: str = "book_collector"):
        """
        初始化数据库连接
        
        Args:
            host: 数据库主机地址
            port: 数据库端口
            user: 数据库用户名
            password: 数据库密码
            database: 数据库名称
        """
        self.host = host
        self.port = port
        self.user = user
        self.password = password
        self.database = database
        self.connection = None
        
        # 配置日志
        logging.basicConfig(level=logging.INFO)
        self.logger = logging.getLogger(__name__)
    
    def connect(self):
        """连接数据库"""
        try:
            self.connection = pymysql.connect(
                host=self.host,
                port=self.port,
                user=self.user,
                password=self.password,
                database=self.database,
                charset='utf8mb4',
                autocommit=True
            )
            self.logger.info("数据库连接成功")
        except Exception as e:
            self.logger.error(f"数据库连接失败: {str(e)}")
            raise
    
    def disconnect(self):
        """断开数据库连接"""
        if self.connection:
            self.connection.close()
            self.logger.info("数据库连接已断开")
    
    def __enter__(self):
        """上下文管理器入口"""
        self.connect()
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        """上下文管理器出口"""
        self.disconnect()
    
    def save_book(self, book: BookInfo) -> bool:
        """
        保存单本图书信息
        
        Args:
            book: 图书信息对象
            
        Returns:
            bool: 是否保存成功
        """
        try:
            with self.connection.cursor() as cursor:
                # 检查图书是否已存在
                cursor.execute("SELECT book_id FROM books WHERE book_id = %s", (book.book_id,))
                if cursor.fetchone():
                    # 更新现有记录
                    self._update_book(cursor, book)
                    self.logger.info(f"更新图书: {book.title}")
                else:
                    # 插入新记录
                    self._insert_book(cursor, book)
                    self.logger.info(f"插入图书: {book.title}")
                
                # 保存关联数据
                self._save_book_categories(cursor, book)
                self._save_book_rating_details(cursor, book)
                # 删除章节相关保存
                # self._save_book_free_chapters(cursor, book)
                # self._save_book_copyright_chapters(cursor, book)
                
                return True
                
        except Exception as e:
            self.logger.error(f"保存图书失败 {book.title}: {str(e)}")
            return False
    
    def _insert_book(self, cursor, book: BookInfo):
        """插入图书信息"""
        sql = """
        INSERT INTO books (
            book_id, title, author, translator, cover, version, format, type,
            price, original_price, soldout, book_status, paying_status, pay_type,
            intro, cent_price, finished, max_free_chapter, free, mcard_discount,
            ispub, extra_type, cpid, publish_time, category, has_lecture,
            last_chapter_idx, paper_book_sku_id, block_save_img, language,
            is_traditional_chinese, hide_update_time, is_epub_comics,
            is_vertical_layout, is_show_tts, web_book_control,
            self_produce_incentive, is_auto_download, new_rating,
            new_rating_count, new_rating_title, search_idx, type_info, reading_count
        ) VALUES (
            %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
            %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
            %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
        )
        """
        
        cursor.execute(sql, (
            book.book_id, book.title, book.author, book.translator, book.cover,
            book.version, book.format, book.type, book.price, book.original_price,
            book.soldout, book.book_status, book.paying_status, book.pay_type,
            book.intro, book.cent_price, book.finished, book.max_free_chapter,
            book.free, book.mcard_discount, book.ispub, book.extra_type, book.cpid,
            book.publish_time, book.category, book.has_lecture, book.last_chapter_idx,
            book.paper_book_sku_id, book.block_save_img, book.language,
            book.is_traditional_chinese, book.hide_update_time, book.is_epub_comics,
            book.is_vertical_layout, book.is_show_tts, book.web_book_control,
            book.self_produce_incentive, book.is_auto_download, book.new_rating,
            book.new_rating_count, book.new_rating_title, book.search_idx,
            book.type_info, book.reading_count
        ))
    
    def _update_book(self, cursor, book: BookInfo):
        """更新图书信息"""
        sql = """
        UPDATE books SET
            title = %s, author = %s, translator = %s, cover = %s, version = %s,
            format = %s, type = %s, price = %s, original_price = %s, soldout = %s,
            book_status = %s, paying_status = %s, pay_type = %s, intro = %s,
            cent_price = %s, finished = %s, max_free_chapter = %s, free = %s,
            mcard_discount = %s, ispub = %s, extra_type = %s, cpid = %s,
            publish_time = %s, category = %s, has_lecture = %s, last_chapter_idx = %s,
            paper_book_sku_id = %s, block_save_img = %s, language = %s,
            is_traditional_chinese = %s, hide_update_time = %s, is_epub_comics = %s,
            is_vertical_layout = %s, is_show_tts = %s, web_book_control = %s,
            self_produce_incentive = %s, is_auto_download = %s, new_rating = %s,
            new_rating_count = %s, new_rating_title = %s, search_idx = %s,
            type_info = %s, reading_count = %s
        WHERE book_id = %s
        """
        
        cursor.execute(sql, (
            book.title, book.author, book.translator, book.cover, book.version,
            book.format, book.type, book.price, book.original_price, book.soldout,
            book.book_status, book.paying_status, book.pay_type, book.intro,
            book.cent_price, book.finished, book.max_free_chapter, book.free,
            book.mcard_discount, book.ispub, book.extra_type, book.cpid,
            book.publish_time, book.category, book.has_lecture, book.last_chapter_idx,
            book.paper_book_sku_id, book.block_save_img, book.language,
            book.is_traditional_chinese, book.hide_update_time, book.is_epub_comics,
            book.is_vertical_layout, book.is_show_tts, book.web_book_control,
            book.self_produce_incentive, book.is_auto_download, book.new_rating,
            book.new_rating_count, book.new_rating_title, book.search_idx,
            book.type_info, book.reading_count, book.book_id
        ))
    
    def _save_book_categories(self, cursor, book: BookInfo):
        """保存图书分类信息"""
        if not book.categories:
            return
        
        # 删除旧的分类信息
        cursor.execute("DELETE FROM book_categories WHERE book_id = %s", (book.book_id,))
        
        # 插入新的分类信息
        for category in book.categories:
            cursor.execute("""
                INSERT INTO book_categories (book_id, category_id, sub_category_id, category_type, title)
                VALUES (%s, %s, %s, %s, %s)
            """, (
                book.book_id,
                category.get('categoryId'),
                category.get('subCategoryId'),
                category.get('categoryType'),
                category.get('title')
            ))
    
    def _save_book_rating_details(self, cursor, book: BookInfo):
        """保存图书评分详情"""
        if not book.new_rating_detail:
            return
        
        # 删除旧的评分详情
        cursor.execute("DELETE FROM book_rating_details WHERE book_id = %s", (book.book_id,))
        
        # 插入新的评分详情
        cursor.execute("""
            INSERT INTO book_rating_details (book_id, good_count, fair_count, poor_count, recent_count)
            VALUES (%s, %s, %s, %s, %s)
        """, (
            book.book_id,
            book.new_rating_detail.get('good', 0),
            book.new_rating_detail.get('fair', 0),
            book.new_rating_detail.get('poor', 0),
            book.new_rating_detail.get('recent', 0)
        ))
    
    def save_api_request(self, category_id: int, max_index: int, request_url: str, 
                        response: APIResponse) -> bool:
        """
        保存API请求记录
        
        Args:
            category_id: 分类ID
            max_index: 最大索引
            request_url: 请求URL
            response: API响应对象
            
        Returns:
            bool: 是否保存成功
        """
        try:
            with self.connection.cursor() as cursor:
                cursor.execute("""
                    INSERT INTO api_requests (
                        category_id, max_index, request_url, response_synckey,
                        total_count, has_more, books_count, status_code, response_time_ms
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                """, (
                    category_id, max_index, request_url, response.synckey,
                    response.total_count, response.has_more, len(response.books),
                    response.status_code, response.response_time_ms
                ))
                
                self.logger.info(f"保存API请求记录: 分类{category_id}, 索引{max_index}")
                return True
                
        except Exception as e:
            self.logger.error(f"保存API请求记录失败: {str(e)}")
            return False
    
    def save_books_batch(self, books: List[BookInfo]) -> int:
        """
        批量保存图书信息
        
        Args:
            books: 图书信息列表
            
        Returns:
            int: 成功保存的图书数量
        """
        success_count = 0
        
        for book in books:
            if self.save_book(book):
                success_count += 1
        
        self.logger.info(f"批量保存完成: 成功{success_count}/{len(books)}本图书")
        return success_count
    
    def get_book_by_id(self, book_id: str) -> Optional[Dict[str, Any]]:
        """
        根据图书ID获取图书信息
        
        Args:
            book_id: 图书ID
            
        Returns:
            Optional[Dict]: 图书信息字典
        """
        try:
            with self.connection.cursor(pymysql.cursors.DictCursor) as cursor:
                cursor.execute("SELECT * FROM books WHERE book_id = %s", (book_id,))
                return cursor.fetchone()
        except Exception as e:
            self.logger.error(f"获取图书信息失败: {str(e)}")
            return None
    
    def get_books_by_category(self, category: str, limit: int = 100) -> List[Dict[str, Any]]:
        """
        根据分类获取图书列表
        
        Args:
            category: 分类名称
            limit: 限制数量
            
        Returns:
            List[Dict]: 图书信息列表
        """
        try:
            with self.connection.cursor(pymysql.cursors.DictCursor) as cursor:
                cursor.execute("""
                    SELECT * FROM books 
                    WHERE category LIKE %s 
                    ORDER BY new_rating DESC, reading_count DESC 
                    LIMIT %s
                """, (f"%{category}%", limit))
                return cursor.fetchall()
        except Exception as e:
            self.logger.error(f"获取分类图书失败: {str(e)}")
            return []
    
    def get_top_rated_books(self, limit: int = 50) -> List[Dict[str, Any]]:
        """
        获取评分最高的图书
        
        Args:
            limit: 限制数量
            
        Returns:
            List[Dict]: 图书信息列表
        """
        try:
            with self.connection.cursor(pymysql.cursors.DictCursor) as cursor:
                cursor.execute("""
                    SELECT * FROM books 
                    WHERE new_rating IS NOT NULL 
                    ORDER BY new_rating DESC, new_rating_count DESC 
                    LIMIT %s
                """, (limit,))
                return cursor.fetchall()
        except Exception as e:
            self.logger.error(f"获取高分图书失败: {str(e)}")
            return []
    
    def get_statistics(self) -> Dict[str, Any]:
        """
        获取数据库统计信息
        
        Returns:
            Dict: 统计信息
        """
        try:
            with self.connection.cursor() as cursor:
                # 总图书数量
                cursor.execute("SELECT COUNT(*) as total FROM books")
                total_books = cursor.fetchone()[0]
                
                # 分类统计
                cursor.execute("""
                    SELECT category, COUNT(*) as count 
                    FROM books 
                    WHERE category IS NOT NULL 
                    GROUP BY category 
                    ORDER BY count DESC 
                    LIMIT 10
                """)
                category_stats = cursor.fetchall()
                
                # 评分统计
                cursor.execute("""
                    SELECT 
                        AVG(new_rating) as avg_rating,
                        MAX(new_rating) as max_rating,
                        MIN(new_rating) as min_rating,
                        COUNT(*) as rated_books
                    FROM books 
                    WHERE new_rating IS NOT NULL
                """)
                rating_stats = cursor.fetchone()
                
                return {
                    'total_books': total_books,
                    'category_stats': category_stats,
                    'rating_stats': {
                        'avg_rating': float(rating_stats[0]) if rating_stats[0] else 0,
                        'max_rating': float(rating_stats[1]) if rating_stats[1] else 0,
                        'min_rating': float(rating_stats[2]) if rating_stats[2] else 0,
                        'rated_books': rating_stats[3]
                    }
                }
                
        except Exception as e:
            self.logger.error(f"获取统计信息失败: {str(e)}")
            return {}


if __name__ == "__main__":
    # 测试代码
    db_config = {
        'host': 'rm-bp1y1m3ez92y1o0l5.mysql.rds.aliyuncs.com',
        'port': 3306,
        'user': 'your_username',  # 需要替换为实际的用户名
        'password': 'your_password',  # 需要替换为实际的密码
        'database': 'book_collector'
    }
    
    try:
        with BookDatabase(**db_config) as db:
            # 获取统计信息
            stats = db.get_statistics()
            print("数据库统计信息:")
            print(f"总图书数量: {stats.get('total_books', 0)}")
            print(f"平均评分: {stats.get('rating_stats', {}).get('avg_rating', 0):.2f}")
            
            # 获取高分图书
            top_books = db.get_top_rated_books(5)
            print("\n评分最高的5本图书:")
            for book in top_books:
                print(f"  {book['title']} - 评分: {book['new_rating']}")
                
    except Exception as e:
        print(f"测试失败: {str(e)}") 