package com.bookcollector.book;

import com.bookcollector.book.dto.BookUpdateRequest;
import com.bookcollector.book.entity.Book;
import com.bookcollector.common.BizException;
import com.bookcollector.common.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图书库业务逻辑。
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
 * <p>不引入接口 + 实现类的分层。单实现的服务写接口是纯仪式，
 * 第一期没有第二个实现的可能。
 */
@Service
public class BookService {

    private static final Logger log = LoggerFactory.getLogger(BookService.class);

    private final BookRepository repository;

    public BookService(BookRepository repository) {
        this.repository = repository;
    }

    /** 分页 + 多条件筛选（FR-B1 / FR-B2） */
    public PageResult<Book> page(BookQuery query) {
        return repository.pageQuery(query);
    }

    /** 详情（FR-B4）。找不到抛 404，让前端能给出「这本书不存在（可能已被删除）」而不是空白抽屉 */
    public Book get(String bookId) {
        Book book = repository.findByBookId(bookId);
        if (book == null) {
            throw BizException.notFound("图书不存在：" + bookId);
        }
        return book;
    }

    /**
     * 编辑（FR-B5）。
     *
     * <p>「只写传了的字段」：{@code null} 一律跳过。所以
     * {@code {"title": "新书名"}} 只会改书名，其余 45 个字段原样不动。
     *
     * <p>全部字段都是 null（空请求体）时直接报错而不是静默成功 ——
     * 「点保存 → 提示成功 → 什么都没变」是最容易让人怀疑系统的交互。
     */
    public Book update(String bookId, BookUpdateRequest req) {
        // 先确认存在，顺便拿到当前值用于返回
        Book current = get(bookId);

        Map<String, Object> sets = new LinkedHashMap<String, Object>();
        putIfNotNull(sets, "title", req.getTitle());
        putIfNotNull(sets, "author", req.getAuthor());
        putIfNotNull(sets, "translator", req.getTranslator());
        putIfNotNull(sets, "intro", req.getIntro());
        putIfNotNull(sets, "category", req.getCategory());
        putIfNotNull(sets, "categories", req.getCategories());
        putIfNotNull(sets, "publishTime", req.getPublishTime());
        putIfNotNull(sets, "language", req.getLanguage());
        putIfNotNull(sets, "ispub", req.getIspub());
        putIfNotNull(sets, "finished", req.getFinished());
        putIfNotNull(sets, "free", req.getFree());
        putIfNotNull(sets, "price", req.getPrice());
        putIfNotNull(sets, "originalPrice", req.getOriginalPrice());

        if (sets.isEmpty()) {
            throw new BizException(com.bookcollector.common.ResultBean.code_warn,
                    "没有需要修改的字段（请求体里至少要有一个字段）");
        }
        repository.updateFields(bookId, sets);
        log.info("图书已编辑：{}，修改字段 {}", bookId, sets.keySet());

        // 返回更新后的最新值，前端可以据此直接刷新抽屉，不用再发一次 GET
        return get(bookId);
    }

    /** 单条删除（FR-B6）。返回删除的 bookId，便于前端给出确认提示 */
    public String delete(String bookId) {
        Book book = get(bookId);
        repository.deleteByBookId(bookId);
        log.info("图书已删除：{}（{}）", bookId, book.getTitle());
        return bookId;
    }

    /**
     * 批量删除（FR-B6）。
     *
     * <p>返回<b>实际删除的条数</b>，而不是请求里的条数。两者可能不同：
     * 前端列表是上一次查询的快照，期间某本书可能已经被另一个标签页删掉了。
     * 报「实际值」比报「请求值」诚实。
     */
    public long batchDelete(List<String> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            throw new BizException(com.bookcollector.common.ResultBean.code_warn,
                    "bookIds 不能为空");
        }
        long deleted = repository.deleteByBookIds(bookIds);
        log.info("批量删除图书：请求 {} 条，实际删除 {} 条", bookIds.size(), deleted);
        return deleted;
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
