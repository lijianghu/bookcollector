import requests
import json
import time
from typing import Dict, List, Optional, Any
from dataclasses import dataclass
from datetime import datetime


@dataclass
class BookInfo:
    """图书信息数据类"""
    book_id: str
    title: str
    author: Optional[str] = None
    translator: Optional[str] = None
    cover: Optional[str] = None
    version: Optional[int] = None
    format: Optional[str] = None
    type: Optional[int] = None
    price: Optional[float] = None
    original_price: Optional[float] = None
    soldout: Optional[int] = None
    book_status: Optional[int] = None
    paying_status: Optional[int] = None
    pay_type: Optional[int] = None
    intro: Optional[str] = None
    cent_price: Optional[int] = None
    finished: Optional[int] = None
    max_free_chapter: Optional[int] = None
    free: Optional[int] = None
    mcard_discount: Optional[int] = None
    ispub: Optional[int] = None
    extra_type: Optional[int] = None
    cpid: Optional[int] = None
    publish_time: Optional[str] = None
    category: Optional[str] = None
    has_lecture: Optional[int] = None
    last_chapter_idx: Optional[int] = None
    paper_book_sku_id: Optional[str] = None
    block_save_img: Optional[int] = None
    language: Optional[str] = None
    is_traditional_chinese: Optional[int] = None
    hide_update_time: Optional[int] = None
    is_epub_comics: Optional[int] = None
    is_vertical_layout: Optional[int] = None
    is_show_tts: Optional[int] = None
    web_book_control: Optional[int] = None
    self_produce_incentive: Optional[int] = None
    is_auto_download: Optional[int] = None
    new_rating: Optional[float] = None
    new_rating_count: Optional[int] = None
    new_rating_title: Optional[str] = None
    search_idx: Optional[int] = None
    type_info: Optional[int] = None
    reading_count: Optional[int] = None
    categories: Optional[List[Dict]] = None
    new_rating_detail: Optional[Dict] = None


@dataclass
class APIResponse:
    """API响应数据类"""
    synckey: int
    books: List[BookInfo]
    has_more: bool
    total_count: int
    status_code: int
    response_time_ms: int


class BookAPIClient:
    """微信读书API客户端"""
    
    def __init__(self, base_url: str = "https://weread.qq.com/web/bookListInCategory"):
        self.base_url = base_url
        self.session = requests.Session()
        self.session.headers.update({
            'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept': 'application/json, text/plain, */*',
            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
            'Accept-Encoding': 'gzip, deflate, br',
            'Connection': 'keep-alive',
            'Referer': 'https://weread.qq.com/',
        })
    
    def _parse_book_info(self, book_data: Dict[str, Any]) -> BookInfo:
        """解析图书信息"""
        book_info = book_data.get('bookInfo', {})
        
        # 辅助函数：处理空字符串
        def clean_value(value):
            """将空字符串转换为None"""
            if value == '' or value == 'null' or value == 'undefined':
                return None
            return value
        
        # 处理纸质书信息
        paper_book = book_info.get('paperBook', {})
        paper_book_sku_id = clean_value(paper_book.get('skuId', '')) if paper_book else None
        
        # 处理评分详情
        new_rating_detail = book_info.get('newRatingDetail', {})
        new_rating_title = clean_value(new_rating_detail.get('title')) if new_rating_detail else None
        
        return BookInfo(
            book_id=clean_value(book_info.get('bookId', '')),
            title=clean_value(book_info.get('title', '')),
            author=clean_value(book_info.get('author')),
            translator=clean_value(book_info.get('translator')),
            cover=clean_value(book_info.get('cover')),
            version=book_info.get('version'),
            format=clean_value(book_info.get('format')),
            type=book_info.get('type'),
            price=book_info.get('price'),
            original_price=book_info.get('originalPrice'),
            soldout=book_info.get('soldout'),
            book_status=book_info.get('bookStatus'),
            paying_status=book_info.get('payingStatus'),
            pay_type=book_info.get('payType'),
            intro=clean_value(book_info.get('intro')),
            cent_price=book_info.get('centPrice'),
            finished=book_info.get('finished'),
            max_free_chapter=book_info.get('maxFreeChapter'),
            free=book_info.get('free'),
            mcard_discount=book_info.get('mcardDiscount'),
            ispub=book_info.get('ispub'),
            extra_type=book_info.get('extra_type'),
            cpid=book_info.get('cpid'),
            publish_time=clean_value(book_info.get('publishTime')),
            category=clean_value(book_info.get('category')),
            has_lecture=book_info.get('hasLecture'),
            last_chapter_idx=book_info.get('lastChapterIdx'),
            paper_book_sku_id=paper_book_sku_id,
            block_save_img=book_info.get('blockSaveImg'),
            language=clean_value(book_info.get('language')),
            is_traditional_chinese=book_info.get('isTraditionalChinese'),
            hide_update_time=book_info.get('hideUpdateTime'),
            is_epub_comics=book_info.get('isEPUBComics'),
            is_vertical_layout=book_info.get('isVerticalLayout'),
            is_show_tts=book_info.get('isShowTTS'),
            web_book_control=book_info.get('webBookControl'),
            self_produce_incentive=book_info.get('selfProduceIncentive'),
            is_auto_download=book_info.get('isAutoDownload'),
            new_rating=book_info.get('newRating'),
            new_rating_count=book_info.get('newRatingCount'),
            new_rating_title=new_rating_title,
            search_idx=book_data.get('searchIdx'),
            type_info=book_data.get('type'),
            reading_count=book_data.get('readingCount'),
            categories=book_info.get('categories'),
            new_rating_detail=new_rating_detail
        )
    
    def fetch_books(self, category_id: int, max_index: int = 0) -> APIResponse:
        """
        获取指定分类的图书列表
        
        Args:
            category_id: 分类ID
            max_index: 分页索引
            
        Returns:
            APIResponse: API响应对象
        """
        url = f"{self.base_url}/{category_id}?maxIndex={max_index}"
        
        start_time = time.time()
        
        try:
            response = self.session.get(url, timeout=30)
            response_time_ms = int((time.time() - start_time) * 1000)
            
            if response.status_code != 200:
                raise Exception(f"API请求失败，状态码: {response.status_code}")
            
            data = response.json()
            
            # 解析图书信息
            books = []
            for book_data in data.get('books', []):
                book_info = self._parse_book_info(book_data)
                books.append(book_info)
            
            return APIResponse(
                synckey=data.get('synckey', 0),
                books=books,
                has_more=data.get('hasMore', False),
                total_count=data.get('totalCount', 0),
                status_code=response.status_code,
                response_time_ms=response_time_ms
            )
            
        except requests.exceptions.RequestException as e:
            raise Exception(f"网络请求错误: {str(e)}")
        except json.JSONDecodeError as e:
            raise Exception(f"JSON解析错误: {str(e)}")
        except Exception as e:
            raise Exception(f"未知错误: {str(e)}")

    def fetch_ranking_books(self, ranking_type: str, max_index: int = 0) -> APIResponse:
        """
        获取榜单图书列表
        
        Args:
            ranking_type: 榜单类型 (rising, hot_search, newbook, etc.)
            max_index: 分页索引
            
        Returns:
            APIResponse: API响应对象
        """
        url = f"{self.base_url}/{ranking_type}?maxIndex={max_index}&rank=1"
        
        start_time = time.time()
        
        try:
            response = self.session.get(url, timeout=30)
            response_time_ms = int((time.time() - start_time) * 1000)
            
            if response.status_code != 200:
                raise Exception(f"API请求失败，状态码: {response.status_code}")
            
            data = response.json()
            
            # 解析图书信息
            books = []
            for book_data in data.get('books', []):
                book_info = self._parse_book_info(book_data)
                books.append(book_info)
            
            return APIResponse(
                synckey=data.get('synckey', 0),
                books=books,
                has_more=data.get('hasMore', False),
                total_count=data.get('totalCount', 0),
                status_code=response.status_code,
                response_time_ms=response_time_ms
            )
            
        except requests.exceptions.RequestException as e:
            raise Exception(f"网络请求错误: {str(e)}")
        except json.JSONDecodeError as e:
            raise Exception(f"JSON解析错误: {str(e)}")
        except Exception as e:
            raise Exception(f"未知错误: {str(e)}")
    
    def fetch_all_books(self, category_id: int, max_pages: int = None) -> List[BookInfo]:
        """
        获取指定分类的所有图书（分页获取）
        
        Args:
            category_id: 分类ID
            max_pages: 最大页数限制，None表示无限制
            
        Returns:
            List[BookInfo]: 所有图书信息列表
        """
        all_books = []
        max_index = 0
        page_count = 0
        
        while True:
            try:
                response = self.fetch_books(category_id, max_index)
                all_books.extend(response.books)
                
                print(f"已获取第 {page_count + 1} 页，图书数量: {len(response.books)}，总计: {len(all_books)}")
                
                if not response.has_more:
                    break
                
                if max_pages and page_count >= max_pages - 1:
                    break
                
                # 更新max_index为当前页的最后一个图书的searchIdx
                if response.books:
                    max_index = response.books[-1].search_idx or max_index
                
                page_count += 1
                
                # 添加延迟避免请求过于频繁
                time.sleep(1)
                
            except Exception as e:
                print(f"获取第 {page_count + 1} 页时出错: {str(e)}")
                break
        
        return all_books

    def fetch_all_ranking_books(self, ranking_type: str, max_pages: int = None) -> List[BookInfo]:
        """
        获取指定榜单的所有图书（分页获取）
        
        Args:
            ranking_type: 榜单类型
            max_pages: 最大页数限制，None表示无限制
            
        Returns:
            List[BookInfo]: 所有图书信息列表
        """
        all_books = []
        max_index = 0
        page_count = 0
        
        while True:
            try:
                response = self.fetch_ranking_books(ranking_type, max_index)
                all_books.extend(response.books)
                
                print(f"已获取榜单 {ranking_type} 第 {page_count + 1} 页，图书数量: {len(response.books)}，总计: {len(all_books)}")
                
                if not response.has_more:
                    break
                
                if max_pages and page_count >= max_pages - 1:
                    break
                
                # 更新max_index为当前页的最后一个图书的searchIdx
                if response.books:
                    max_index = response.books[-1].search_idx or max_index
                
                page_count += 1
                
                # 添加延迟避免请求过于频繁
                time.sleep(1)
                
            except Exception as e:
                print(f"获取榜单 {ranking_type} 第 {page_count + 1} 页时出错: {str(e)}")
                break
        
        return all_books


if __name__ == "__main__":
    # 测试代码
    client = BookAPIClient()
    
    try:
        # 获取第一页数据
        response = client.fetch_books(300000, 0)
        print(f"获取到 {len(response.books)} 本图书")
        print(f"总数量: {response.total_count}")
        print(f"是否有更多: {response.has_more}")
        
        # 打印前3本图书信息
        for i, book in enumerate(response.books[:3]):
            print(f"\n图书 {i+1}:")
            print(f"  标题: {book.title}")
            print(f"  作者: {book.author}")
            print(f"  评分: {book.new_rating}")
            print(f"  价格: {book.price}")
            
    except Exception as e:
        print(f"错误: {str(e)}") 