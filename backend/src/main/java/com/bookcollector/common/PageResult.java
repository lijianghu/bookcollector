package com.bookcollector.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分页结果包装。统一放在 {@code ResultBean.data} 里。
 *
 * <pre>
 * {
 *   "code": 200,
 *   "data": { "list": [...], "total": 12345, "page": 1, "size": 20 }
 * }
 * </pre>
 *
 * @param <T> 列表元素类型
 */
@Schema(description = "分页结果")
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页数据 */
    private List<T> list = new ArrayList<T>();
    /** 总条数 */
    private long total;
    /** 当前页码，从 1 开始 */
    private int page = 1;
    /** 每页条数 */
    private int size = 20;

    public PageResult() {
    }

    public PageResult(List<T> list, long total, int page, int size) {
        this.list = list == null ? new ArrayList<T>() : list;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        return new PageResult<T>(list, total, page, size);
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<T>(new ArrayList<T>(), 0L, page, size);
    }

    /** 总页数 */
    public int getTotalPages() {
        if (size <= 0) {
            return 0;
        }
        return (int) ((total + size - 1) / size);
    }

    public List<T> getList() {
        return list;
    }

    public void setList(List<T> list) {
        this.list = list;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
