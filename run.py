#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
快速启动脚本
提供简单的命令行界面来运行图书收集器
"""

import sys
import argparse
from book_collector import BookCollector
from config import DATABASE_CONFIG, CATEGORIES, RANKING_LISTS


def show_categories():
    """显示可用的分类"""
    print("可用的分类:")
    for category_id, name in CATEGORIES.items():
        print(f"  {category_id}: {name}")


def show_rankings():
    """显示可用的榜单"""
    print("可用的榜单:")
    for ranking_type, name in RANKING_LISTS.items():
        print(f"  {ranking_type}: {name}")


def collect_books(category_ids, max_pages):
    """收集图书"""
    print(f"开始收集分类 {category_ids} 的图书信息...")
    
    collector = BookCollector(DATABASE_CONFIG)
    results = collector.collect_multiple_categories(category_ids, max_pages)
    
    print("\n收集结果:")
    total_books = 0
    for category_id, result in results.items():
        category_name = CATEGORIES.get(category_id, f"分类{category_id}")
        if result['success']:
            book_count = result['book_count']
            total_books += book_count
            print(f"✅ {category_name}: 成功收集 {book_count} 本图书")
        else:
            print(f"❌ {category_name}: 收集失败 - {result.get('error', '未知错误')}")
    
    print(f"\n总计收集: {total_books} 本图书")


def collect_all_books(max_pages):
    """收集所有分类的图书"""
    print("开始收集所有分类的图书信息...")
    print(f"将收集 {len(CATEGORIES)} 个分类的图书")
    
    collector = BookCollector(DATABASE_CONFIG)
    all_category_ids = list(CATEGORIES.keys())
    results = collector.collect_multiple_categories(all_category_ids, max_pages)
    
    print("\n收集结果:")
    total_books = 0
    success_count = 0
    failed_count = 0
    
    for category_id, result in results.items():
        category_name = CATEGORIES.get(category_id, f"分类{category_id}")
        if result['success']:
            book_count = result['book_count']
            total_books += book_count
            success_count += 1
            print(f"✅ {category_name}: 成功收集 {book_count} 本图书")
        else:
            failed_count += 1
            print(f"❌ {category_name}: 收集失败 - {result.get('error', '未知错误')}")
    
    print(f"\n📊 总结:")
    print(f"   成功分类: {success_count}/{len(CATEGORIES)}")
    print(f"   失败分类: {failed_count}/{len(CATEGORIES)}")
    print(f"   总计收集: {total_books} 本图书")


def collect_ranking_books(ranking_types, max_pages):
    """收集榜单图书"""
    print(f"开始收集榜单 {ranking_types} 的图书信息...")
    
    collector = BookCollector(DATABASE_CONFIG)
    results = collector.collect_multiple_rankings(ranking_types, max_pages)
    
    print("\n收集结果:")
    total_books = 0
    for ranking_type, result in results.items():
        ranking_name = RANKING_LISTS.get(ranking_type, f"榜单{ranking_type}")
        if result['success']:
            book_count = result['book_count']
            total_books += book_count
            print(f"✅ {ranking_name}: 成功收集 {book_count} 本图书")
        else:
            print(f"❌ {ranking_name}: 收集失败 - {result.get('error', '未知错误')}")
    
    print(f"\n总计收集: {total_books} 本图书")


def collect_all_rankings(max_pages):
    """收集所有榜单的图书"""
    print("开始收集所有榜单的图书信息...")
    print(f"将收集 {len(RANKING_LISTS)} 个榜单的图书")
    
    collector = BookCollector(DATABASE_CONFIG)
    all_ranking_types = list(RANKING_LISTS.keys())
    results = collector.collect_multiple_rankings(all_ranking_types, max_pages)
    
    print("\n收集结果:")
    total_books = 0
    success_count = 0
    failed_count = 0
    
    for ranking_type, result in results.items():
        ranking_name = RANKING_LISTS.get(ranking_type, f"榜单{ranking_type}")
        if result['success']:
            book_count = result['book_count']
            total_books += book_count
            success_count += 1
            print(f"✅ {ranking_name}: 成功收集 {book_count} 本图书")
        else:
            failed_count += 1
            print(f"❌ {ranking_name}: 收集失败 - {result.get('error', '未知错误')}")
    
    print(f"\n📊 总结:")
    print(f"   成功榜单: {success_count}/{len(RANKING_LISTS)}")
    print(f"   失败榜单: {failed_count}/{len(RANKING_LISTS)}")
    print(f"   总计收集: {total_books} 本图书")


def collect_books_from_index(category_id, start_index, max_pages):
    """从指定索引开始收集图书"""
    print(f"从索引 {start_index} 开始收集分类 {category_id} 的图书...")
    
    collector = BookCollector(DATABASE_CONFIG)
    result = collector.collect_books_from_index(category_id, start_index, max_pages)
    
    print(f"\n收集结果:")
    print(f"  分类ID: {result['category_id']}")
    print(f"  起始索引: {result['start_index']}")
    print(f"  最后索引: {result['last_index']}")
    print(f"  收集图书: {result['book_count']} 本")


def continue_collection(category_id, max_pages):
    """继续上次未完成的收集"""
    print(f"继续收集分类 {category_id} 的图书...")
    
    collector = BookCollector(DATABASE_CONFIG)
    result = collector.continue_collection(category_id, max_pages)
    
    print(f"\n继续收集结果:")
    print(f"  分类ID: {result['category_id']}")
    print(f"  起始索引: {result['start_index']}")
    print(f"  最后索引: {result['last_index']}")
    print(f"  收集图书: {result['book_count']} 本")


def show_last_index(category_id):
    """显示指定分类的最后请求索引"""
    print(f"获取分类 {category_id} 的最后请求索引...")
    
    collector = BookCollector(DATABASE_CONFIG)
    last_index = collector.get_last_request_index(category_id)
    
    print(f"分类 {category_id} 的最后请求索引: {last_index}")


def show_statistics():
    """显示统计信息"""
    print("获取数据库统计信息...")
    
    collector = BookCollector(DATABASE_CONFIG)
    stats = collector.get_database_statistics()
    
    print(f"\n📊 数据库统计:")
    print(f"   总图书数量: {stats.get('total_books', 0)}")
    
    rating_stats = stats.get('rating_stats', {})
    if rating_stats:
        print(f"   平均评分: {rating_stats.get('avg_rating', 0):.2f}")
        print(f"   最高评分: {rating_stats.get('max_rating', 0):.2f}")
        print(f"   最低评分: {rating_stats.get('min_rating', 0):.2f}")
        print(f"   有评分的图书: {rating_stats.get('rated_books', 0)}")
    
    # 显示分类统计
    category_stats = stats.get('category_stats', [])
    if category_stats:
        print(f"\n📚 分类统计 (前5个):")
        for category, count in category_stats[:5]:
            print(f"   {category}: {count} 本")


def show_top_books(limit):
    """显示高分图书"""
    print(f"获取评分最高的 {limit} 本图书...")
    
    collector = BookCollector(DATABASE_CONFIG)
    top_books = collector.get_top_books(limit)
    
    print(f"\n🏆 评分最高的 {len(top_books)} 本图书:")
    for i, book in enumerate(top_books, 1):
        print(f"   {i:2d}. {book['title']}")
        print(f"       作者: {book['author']}")
        print(f"       评分: {book['new_rating']} ({book['new_rating_count']}人评价)")
        print(f"       价格: {book['price']} 元")
        print()


def main():
    """主函数"""
    parser = argparse.ArgumentParser(description="图书收集器命令行工具")
    parser.add_argument('--categories', '-c', action='store_true', 
                       help='显示可用的分类')
    parser.add_argument('--collect', '-col', nargs='+', type=int,
                       help='收集指定分类的图书 (例如: -col 300000 100000)')
    parser.add_argument('--all', '-a', action='store_true',
                       help='收集所有分类的图书')
    parser.add_argument('--rankings', '-r', action='store_true',
                       help='显示可用的榜单')
    parser.add_argument('--ranking', '-rank', nargs='+', type=str,
                       help='收集指定榜单的图书 (例如: -rank rising hot_search)')
    parser.add_argument('--all-rankings', '-ar', action='store_true',
                       help='收集所有榜单的图书')
    parser.add_argument('--pages', '-p', type=int, default=10,
                       help='每个分类/榜单的最大页数 (默认: 10)')
    parser.add_argument('--stats', '-s', action='store_true',
                       help='显示数据库统计信息')
    parser.add_argument('--top', '-t', type=int, nargs='?', const=10,
                       help='显示评分最高的图书数量 (默认: 10)')
    parser.add_argument('--test', action='store_true',
                       help='测试API连接')
    # 新增参数
    parser.add_argument('--from-index', '-fi', nargs=2, type=int,
                       help='从指定索引开始收集 (例如: -fi 300000 1000)')
    parser.add_argument('--continue-collection', '-cont', type=int,
                       help='继续上次未完成的收集 (例如: -cont 300000)')
    parser.add_argument('--last-index', '-li', type=int,
                       help='显示指定分类的最后请求索引 (例如: -li 300000)')
    
    args = parser.parse_args()
    
    if not any([args.categories, args.collect, args.all, args.rankings, args.ranking, args.all_rankings, 
                args.stats, args.top, args.test, args.from_index, args.continue_collection, args.last_index]):
        parser.print_help()
        return
    
    try:
        if args.categories:
            show_categories()
        
        elif args.collect:
            collect_books(args.collect, args.pages)
        
        elif args.all:
            collect_all_books(args.pages)
        
        elif args.rankings:
            show_rankings()
        
        elif args.ranking:
            collect_ranking_books(args.ranking, args.pages)
        
        elif args.all_rankings:
            collect_all_rankings(args.pages)
        
        elif args.stats:
            show_statistics()
        
        elif args.top is not None:
            show_top_books(args.top)
        
        elif args.test:
            print("测试API连接...")
            from test_api import test_api_request
            test_api_request()
        
        elif args.from_index:
            category_id, start_index = args.from_index
            collect_books_from_index(category_id, start_index, args.pages)
        
        elif args.continue_collection:
            continue_collection(args.continue_collection, args.pages)
        
        elif args.last_index:
            show_last_index(args.last_index)
        
    except Exception as e:
        print(f"❌ 执行失败: {str(e)}")
        print("请检查数据库配置和网络连接")


if __name__ == "__main__":
    main() 