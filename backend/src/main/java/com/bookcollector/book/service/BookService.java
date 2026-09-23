package com.bookcollector.book.service;

import com.bookcollector.book.entity.Book;
import com.bookcollector.book.req.BookQuery;
import com.bookcollector.book.req.BookUpdateRequest;
import com.bookcollector.common.PageResult;

import java.util.List;

/**
 * 图书库业务逻辑（FR-B1 ~ FR-B6）。
 *
 * <h3>为什么在 {@code BookRepository} 之上再包一层</h3>
 * Repository 只负责「怎么和 Mongo 说话」，Service 负责「业务规则」。
 * 目前规则只有三条，但它们都不该下沉到 Repository：
 * <ol>
 *   <li>找不到书要抛 404（而不是返回 null 让 Controller 自己判）</li>
 *   <li>编辑时「传了哪些字段就改哪些」的白名单组装</li>
 *   <li>删除前确认这本书真的存在（否则「删除成功」是个谎话）</li>
 * </ol>
 *
 * @see com.bookcollector.book.service.impl.BookServiceImpl
 */
public interface BookService {

    /** 分页 + 多条件筛选（FR-B1 / FR-B2） */
    PageResult<Book> page(BookQuery query);

    /** 详情（FR-B4）。找不到抛 404，让前端能给出「这本书不存在（可能已被删除）」而不是空白抽屉 */
    Book get(String bookId);

    /**
     * 编辑（FR-B5）。
     *
     * <p>「只写传了的字段」：{@code null} 一律跳过。所以
     * {@code {"title": "新书名"}} 只会改书名，其余 45 个字段原样不动。
     *
     * <p>全部字段都是 null（空请求体）时直接报错而不是静默成功 ——
     * 「点保存 → 提示成功 → 什么都没变」是最容易让人怀疑系统的交互。
     */
    Book update(String bookId, BookUpdateRequest req);

    /** 单条删除（FR-B6）。返回删除的 bookId，便于前端给出确认提示 */
    String delete(String bookId);

    /**
     * 批量删除（FR-B6）。
     *
     * <p>返回<b>实际删除的条数</b>，而不是请求里的条数。两者可能不同：
     * 前端列表是上一次查询的快照，期间某本书可能已经被另一个标签页删掉了。
     * 报「实际值」比报「请求值」诚实。
     */
    long batchDelete(List<String> bookIds);
}
