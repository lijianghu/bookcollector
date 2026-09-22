package com.bookcollector.book.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 批量删除请求体（FR-B6）。
 *
 * <p>用 {@code POST /api/books/batch-delete} 而不是 {@code DELETE} 带 body：
 * HTTP 规范上 DELETE 的 body 语义不明确，部分代理会直接丢掉它，
 * 而且 {@code axios.delete(url, {data})} 这种写法容易踩坑。POST 一个动作路径最稳。
 */
@Schema(description = "批量删除请求")
public class BatchDeleteRequest {

    @Schema(description = "要删除的 bookId 列表", required = true)
    @NotEmpty(message = "不能为空")
    private List<String> bookIds;

    public List<String> getBookIds() {
        return bookIds;
    }

    public void setBookIds(List<String> bookIds) {
        this.bookIds = bookIds;
    }
}
