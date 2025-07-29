#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
图书收集器主程序
用于从微信读书API获取图书信息并存储到MySQL数据库
"""

import time
import logging
from typing import List, Optional
from book_api_client import BookAPIClient, BookInfo, APIResponse
from book_database import BookDatabase
from config import DATABASE_CONFIG, COLLECTION_CONFIG, CATEGORIES, RANKING_LISTS


class BookCollector:
    """图书收集器主类"""
    
    def __init__(self, db_config: dict):
        """
        初始化图书收集器
        
        Args:
            db_config: 数据库配置字典
        """
        self.api_client = BookAPIClient()
        self.db_config = db_config
        
        # 配置日志
        logging.basicConfig(
            level=logging.INFO,
            format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
            handlers=[
                logging.FileHandler('book_collector.log', encoding='utf-8'),
                logging.StreamHandler()
            ]
        )
        self.logger = logging.getLogger(__name__)
    
    def collect_books_by_category(self, category_id: int, max_pages: int = None, 
                                save_api_records: bool = True, start_max_index: int = 0) -> List[BookInfo]:
        """
        收集指定分类的所有图书
        
        Args:
            category_id: 分类ID
            max_pages: 最大页数限制，None表示无限制
            save_api_records: 是否保存API请求记录
            start_max_index: 开始的分页索引，用于继续上次未完成的请求
            
        Returns:
            List[BookInfo]: 收集到的图书列表
        """
        self.logger.info(f"开始收集分类 {category_id} 的图书信息，起始索引: {start_max_index}")
        
        all_books = []
        max_index = start_max_index
        page_count = 0
        
        with BookDatabase(**self.db_config) as db:
            while True:
                try:
                    # 获取API数据
                    response = self.api_client.fetch_books(category_id, max_index)
                    all_books.extend(response.books)
                    
                    self.logger.info(f"第 {page_count + 1} 页: 获取到 {len(response.books)} 本图书，总计: {len(all_books)}，当前索引: {max_index}")
                    
                    # 保存API请求记录
                    if save_api_records:
                        request_url = f"https://weread.qq.com/web/bookListInCategory/{category_id}?maxIndex={max_index}"
                        db.save_api_request(category_id, max_index, request_url, response)
                    
                    # 批量保存图书信息
                    if response.books:
                        success_count = db.save_books_batch(response.books)
                        self.logger.info(f"成功保存 {success_count}/{len(response.books)} 本图书")
                    
                    # 检查是否还有更多数据
                    if not response.has_more:
                        self.logger.info("已获取所有数据")
                        break
                    
                    # 检查页数限制
                    if max_pages and page_count >= max_pages - 1:
                        self.logger.info(f"已达到最大页数限制: {max_pages}")
                        break
                    
                    # 更新分页索引
                    if response.books:
                        max_index = response.books[-1].search_idx or max_index
                    
                    page_count += 1
                    
                    # 添加延迟避免请求过于频繁
                    time.sleep(1)
                    
                except Exception as e:
                    self.logger.error(f"获取第 {page_count + 1} 页时出错: {str(e)}")
                    break
        
        self.logger.info(f"分类 {category_id} 收集完成，共获取 {len(all_books)} 本图书，最后索引: {max_index}")
        return all_books

    def collect_ranking_books(self, ranking_type: str, max_pages: int = None, 
                             save_api_records: bool = True, start_max_index: int = 0) -> List[BookInfo]:
        """
        收集指定榜单的所有图书
        
        Args:
            ranking_type: 榜单类型
            max_pages: 最大页数限制，None表示无限制
            save_api_records: 是否保存API请求记录
            start_max_index: 开始的分页索引，用于继续上次未完成的请求
            
        Returns:
            List[BookInfo]: 收集到的图书列表
        """
        self.logger.info(f"开始收集榜单 {ranking_type} 的图书信息，起始索引: {start_max_index}")
        
        all_books = []
        max_index = start_max_index
        page_count = 0
        
        with BookDatabase(**self.db_config) as db:
            while True:
                try:
                    # 获取API数据
                    response = self.api_client.fetch_ranking_books(ranking_type, max_index)
                    all_books.extend(response.books)
                    
                    self.logger.info(f"第 {page_count + 1} 页: 获取到 {len(response.books)} 本图书，总计: {len(all_books)}，当前索引: {max_index}")
                    
                    # 保存API请求记录
                    if save_api_records:
                        request_url = f"https://weread.qq.com/web/bookListInCategory/{ranking_type}?maxIndex={max_index}&rank=1"
                        # 使用特殊ID来标识榜单请求
                        db.save_api_request(-1, max_index, request_url, response)
                    
                    # 批量保存图书信息
                    if response.books:
                        success_count = db.save_books_batch(response.books)
                        self.logger.info(f"成功保存 {success_count}/{len(response.books)} 本图书")
                    
                    # 检查是否还有更多数据
                    if not response.has_more:
                        self.logger.info("已获取所有数据")
                        break
                    
                    # 检查页数限制
                    if max_pages and page_count >= max_pages - 1:
                        self.logger.info(f"已达到最大页数限制: {max_pages}")
                        break
                    
                    # 更新分页索引
                    if response.books:
                        max_index = response.books[-1].search_idx or max_index
                    
                    page_count += 1
                    
                    # 添加延迟避免请求过于频繁
                    time.sleep(1)
                    
                except Exception as e:
                    self.logger.error(f"获取第 {page_count + 1} 页时出错: {str(e)}")
                    break
        
        return all_books

    def collect_multiple_rankings(self, ranking_types: List[str], max_pages_per_ranking: int = None) -> dict:
        """
        收集多个榜单的图书
        
        Args:
            ranking_types: 榜单类型列表
            max_pages_per_ranking: 每个榜单的最大页数限制
            
        Returns:
            dict: 收集结果统计
        """
        self.logger.info(f"开始收集 {len(ranking_types)} 个榜单的图书信息")
        
        results = {}
        total_books = 0
        
        for ranking_type in ranking_types:
            try:
                self.logger.info(f"开始收集榜单: {ranking_type}")
                books = self.collect_ranking_books(ranking_type, max_pages_per_ranking)
                
                results[ranking_type] = {
                    'success': True,
                    'book_count': len(books),
                    'error': None
                }
                total_books += len(books)
                
                self.logger.info(f"榜单 {ranking_type} 收集完成，共 {len(books)} 本图书")
                
            except Exception as e:
                self.logger.error(f"收集榜单 {ranking_type} 时出错: {str(e)}")
                results[ranking_type] = {
                    'success': False,
                    'book_count': 0,
                    'error': str(e)
                }
        
        self.logger.info(f"所有榜单收集完成，总计: {total_books} 本图书")
        return results
    
    def collect_books_from_index(self, category_id: int, start_max_index: int, 
                               max_pages: int = None, save_api_records: bool = True) -> dict:
        """
        从指定索引开始收集图书
        
        Args:
            category_id: 分类ID
            start_max_index: 开始的分页索引
            max_pages: 最大页数限制
            save_api_records: 是否保存API请求记录
            
        Returns:
            dict: 收集结果，包含图书列表和最后索引
        """
        self.logger.info(f"从索引 {start_max_index} 开始收集分类 {category_id} 的图书")
        
        books = self.collect_books_by_category(
            category_id, 
            max_pages=max_pages, 
            save_api_records=save_api_records,
            start_max_index=start_max_index
        )
        
        # 获取最后使用的索引
        last_index = start_max_index
        if books:
            last_index = books[-1].search_idx if books[-1].search_idx else start_max_index
        
        return {
            'category_id': category_id,
            'start_index': start_max_index,
            'last_index': last_index,
            'books': books,
            'book_count': len(books)
        }
    
    def get_last_request_index(self, category_id: int) -> int:
        """
        获取指定分类的最后请求索引
        
        Args:
            category_id: 分类ID
            
        Returns:
            int: 最后请求的索引，如果没有记录返回0
        """
        with BookDatabase(**self.db_config) as db:
            try:
                with db.connection.cursor() as cursor:
                    cursor.execute("""
                        SELECT max_index FROM api_requests 
                        WHERE category_id = %s 
                        ORDER BY created_at DESC 
                        LIMIT 1
                    """, (category_id,))
                    result = cursor.fetchone()
                    return result[0] if result else 0
            except Exception as e:
                self.logger.error(f"获取最后请求索引失败: {str(e)}")
                return 0
    
    def continue_collection(self, category_id: int, max_pages: int = None) -> dict:
        """
        继续上次未完成的收集
        
        Args:
            category_id: 分类ID
            max_pages: 最大页数限制
            
        Returns:
            dict: 收集结果
        """
        last_index = self.get_last_request_index(category_id)
        self.logger.info(f"继续收集分类 {category_id}，从索引 {last_index} 开始")
        
        return self.collect_books_from_index(category_id, last_index, max_pages)
    
    def collect_multiple_categories(self, category_ids: List[int], max_pages_per_category: int = None) -> dict:
        """
        收集多个分类的图书
        
        Args:
            category_ids: 分类ID列表
            max_pages_per_category: 每个分类的最大页数
            
        Returns:
            dict: 收集结果统计
        """
        results = {}
        
        for category_id in category_ids:
            self.logger.info(f"开始收集分类 {category_id}")
            try:
                books = self.collect_books_by_category(category_id, max_pages_per_category)
                results[category_id] = {
                    'success': True,
                    'book_count': len(books),
                    'books': books
                }
            except Exception as e:
                self.logger.error(f"收集分类 {category_id} 失败: {str(e)}")
                results[category_id] = {
                    'success': False,
                    'error': str(e),
                    'book_count': 0,
                    'books': []
                }
        
        return results
    
    def get_database_statistics(self) -> dict:
        """
        获取数据库统计信息
        
        Returns:
            dict: 统计信息
        """
        with BookDatabase(**self.db_config) as db:
            return db.get_statistics()
    
    def search_books(self, keyword: str, limit: int = 50) -> List[dict]:
        """
        搜索图书
        
        Args:
            keyword: 搜索关键词
            limit: 返回结果数量限制
            
        Returns:
            List[dict]: 搜索结果
        """
        with BookDatabase(**self.db_config) as db:
            # 这里可以实现搜索功能，暂时返回空列表
            # 可以根据需要扩展搜索功能
            return []
    
    def get_top_books(self, limit: int = 50) -> List[dict]:
        """
        获取评分最高的图书
        
        Args:
            limit: 返回数量限制
            
        Returns:
            List[dict]: 高分图书列表
        """
        with BookDatabase(**self.db_config) as db:
            return db.get_top_rated_books(limit)
    
    def get_books_by_category_name(self, category_name: str, limit: int = 100) -> List[dict]:
        """
        根据分类名称获取图书
        
        Args:
            category_name: 分类名称
            limit: 返回数量限制
            
        Returns:
            List[dict]: 图书列表
        """
        with BookDatabase(**self.db_config) as db:
            return db.get_books_by_category(category_name, limit)


def main():
    """主函数"""
    # 使用配置文件中的数据库配置
    db_config = DATABASE_CONFIG.copy()
    
    # 创建收集器实例
    collector = BookCollector(db_config)
    
    # 定义要收集的分类ID
    category_ids = [300000, 100000]  # 文学、精品小说
    
    try:
        # 收集图书信息
        print("开始收集图书信息...")
        results = collector.collect_multiple_categories(category_ids, max_pages_per_category=5)
        
        # 打印收集结果
        print("\n收集结果:")
        for category_id, result in results.items():
            if result['success']:
                print(f"分类 {category_id}: 成功收集 {result['book_count']} 本图书")
            else:
                print(f"分类 {category_id}: 收集失败 - {result.get('error', '未知错误')}")
        
        # 获取数据库统计信息
        print("\n数据库统计信息:")
        stats = collector.get_database_statistics()
        print(f"总图书数量: {stats.get('total_books', 0)}")
        print(f"平均评分: {stats.get('rating_stats', {}).get('avg_rating', 0):.2f}")
        
        # 获取高分图书
        print("\n评分最高的10本图书:")
        top_books = collector.get_top_books(10)
        for i, book in enumerate(top_books, 1):
            print(f"{i:2d}. {book['title']} - 评分: {book['new_rating']} ({book['new_rating_count']}人评价)")
        
    except Exception as e:
        print(f"程序执行失败: {str(e)}")


if __name__ == "__main__":
    main() 