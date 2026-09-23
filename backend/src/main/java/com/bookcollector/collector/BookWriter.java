package com.bookcollector.collector;

import com.bookcollector.book.entity.Book;
import com.bookcollector.book.repository.BookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把解析好的图书批量落库。
 *
 * <p>这一层很薄，存在的意义是<b>分片</b>：一次任务可能拿到上千本，
 * 而 {@code bulkOps} 的单个命令有 BSON 16MB 上限。按 {@value #BATCH_SIZE} 一批切开，
 * 既避开上限，也让「一批失败」的爆炸半径可控。
 *
 * <p>真正的 upsert 语义（幂等、{@code $setOnInsert}、{@code $addToSet}）
 * 在 {@link BookRepository#upsertMany} 里，不在这里重复实现。
 */
@Component
public class BookWriter {

    private static final Logger log = LoggerFactory.getLogger(BookWriter.class);

    /** 单批条数。500 本约 1MB 左右，离 16MB 上限很远 */
    public static final int BATCH_SIZE = 500;

    private final BookRepository bookRepository;

    public BookWriter(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    /**
     * 分片批量 upsert。
     *
     * @param books     待写入的图书
     * @param sourceKey 采集来源，形如 {@code "category:300000"}
     * @return 实际写入/更新的条数（累加各批结果）
     */
    public int upsertAll(List<Book> books, String sourceKey) {
        if (books == null || books.isEmpty()) {
            return 0;
        }
        int total = 0;
        int batches = 0;
        for (int from = 0; from < books.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, books.size());
            List<Book> chunk = books.subList(from, to);
            total += bookRepository.upsertMany(chunk, sourceKey);
            batches++;
        }
        if (batches > 1) {
            log.debug("分 {} 批写入 {} 本（source={}）", batches, books.size(), sourceKey);
        }
        return total;
    }
}
