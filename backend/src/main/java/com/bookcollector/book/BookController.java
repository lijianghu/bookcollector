package com.bookcollector.book;

import com.bookcollector.book.dto.BatchDeleteRequest;
import com.bookcollector.book.dto.BookUpdateRequest;
import com.bookcollector.book.entity.Book;
import com.bookcollector.common.PageResult;
import com.bookcollector.common.ResultBean;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图书库（FR-B1 ~ FR-B6）。
 *
 * <p>分页列表直接收 {@link BookQuery} 作为查询参数对象 —— Spring MVC 会把
 * {@code ?categoryId=300000&minRating=800&page=2} 自动绑上去。
 * 不写 {@code @RequestParam} 逐个声明，是因为查询条件有 12 个，
 * 逐个声明只会让签名长到看不见重点，而绑定规则完全一样。
 */
@Tag(name = "02. 图书库", description = "分页筛选 / 详情 / 编辑 / 删除")
@RestController
@RequestMapping("/api/books")
public class BookController {

    private final BookService service;

    public BookController(BookService service) {
        this.service = service;
    }

    @Operation(summary = "分页查询图书",
            description = "支持按 分类ID / 评分区间 / 阅读人数下限 / 采集来源 / 作者 / 出版时间区间 筛选，"
                    + "支持按 评分/阅读人数/评价人数/出版时间/采集时间 排序。"
                    + "注意：不做模糊搜索，author 是精确匹配。")
    @GetMapping
    public ResultBean<PageResult<Book>> page(BookQuery query) {
        return ResultBean.ok(service.page(query));
    }

    @Operation(summary = "图书详情", description = "返回全部 46 个业务字段 + 采集元数据")
    @GetMapping("/{bookId}")
    public ResultBean<Book> detail(
            @Parameter(description = "图书 ID", required = true) @PathVariable String bookId) {
        return ResultBean.ok(service.get(bookId));
    }

    @Operation(summary = "编辑图书", description = "局部更新：只改请求体里出现的字段，其余原样保留")
    @PutMapping("/{bookId}")
    public ResultBean<Book> update(
            @Parameter(description = "图书 ID", required = true) @PathVariable String bookId,
            @Validated @RequestBody BookUpdateRequest req) {
        return ResultBean.ok(service.update(bookId, req));
    }

    @Operation(summary = "删除图书", description = "注意：下次采集到这本书时它会被重新写入（删除不是永久拉黑）")
    @DeleteMapping("/{bookId}")
    public ResultBean<Map<String, Object>> delete(
            @Parameter(description = "图书 ID", required = true) @PathVariable String bookId) {
        String deleted = service.delete(bookId);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("bookId", deleted);
        data.put("deleted", 1);
        return ResultBean.ok(data);
    }

    @Operation(summary = "批量删除图书", description = "返回实际删除条数（可能与请求条数不同）")
    @PostMapping("/batch-delete")
    public ResultBean<Map<String, Object>> batchDelete(
            @Validated @RequestBody BatchDeleteRequest req) {
        long deleted = service.batchDelete(req.getBookIds());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("requested", req.getBookIds().size());
        data.put("deleted", deleted);
        return ResultBean.ok(data);
    }
}
