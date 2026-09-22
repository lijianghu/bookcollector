package com.bookcollector.book.dto;

import com.bookcollector.book.entity.Book;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 图书编辑请求（FR-B5）。
 *
 * <h3>为什么字段是「白名单」而不是直接收一个 {@code Book}</h3>
 * 直接收 {@code Book} 有两个问题：
 * <ol>
 *   <li><b>接口字段会变成可写的</b>。{@code bookId}、{@code searchIdx}、
 *       {@code collectSource}、{@code firstCollectedAt} 这些是采集链路的账本，
 *       前端不该能改。收 {@code Book} 就等于把它们全开放了。</li>
 *   <li><b>无法区分「没传」和「传了 null」</b>。Jackson 反序列化出的
 *       {@code Book} 里，没传的字段和显式传 null 的字段长得一模一样。
 *       白名单 + 「只写非 null」才能表达「局部更新」。</li>
 * </ol>
 *
 * <p>字段清一色用包装类型（{@code Integer} 而不是 {@code int}）就是为了让
 * {@code null} 能表达「这个字段不改」。
 *
 * <p><b>注意</b>：{@code bookId} 不在请求体里，它在 URL 路径上。
 */
@Schema(description = "图书编辑请求（只写传了的字段）")
public class BookUpdateRequest {

    @Schema(description = "书名")
    private String title;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "译者")
    private String translator;

    @Schema(description = "简介")
    private String intro;

    @Schema(description = "分类的字符串形式，如「精品小说-社会小说」")
    private String category;

    @Schema(description = "平台分类标签列表（整体替换）")
    private List<Book.BookCategory> categories;

    @Schema(description = "出版时间，形如 2022-08-01 00:00:00")
    private String publishTime;

    @Schema(description = "语言，如 zh-wr")
    private String language;

    @Schema(description = "是否出版")
    private Integer ispub;

    @Schema(description = "是否完结")
    private Integer finished;

    @Schema(description = "是否免费")
    private Integer free;

    @Schema(description = "价格（元）")
    private Integer price;

    @Schema(description = "原价（元）")
    private Integer originalPrice;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getTranslator() {
        return translator;
    }

    public void setTranslator(String translator) {
        this.translator = translator;
    }

    public String getIntro() {
        return intro;
    }

    public void setIntro(String intro) {
        this.intro = intro;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public List<Book.BookCategory> getCategories() {
        return categories;
    }

    public void setCategories(List<Book.BookCategory> categories) {
        this.categories = categories;
    }

    public String getPublishTime() {
        return publishTime;
    }

    public void setPublishTime(String publishTime) {
        this.publishTime = publishTime;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Integer getIspub() {
        return ispub;
    }

    public void setIspub(Integer ispub) {
        this.ispub = ispub;
    }

    public Integer getFinished() {
        return finished;
    }

    public void setFinished(Integer finished) {
        this.finished = finished;
    }

    public Integer getFree() {
        return free;
    }

    public void setFree(Integer free) {
        this.free = free;
    }

    public Integer getPrice() {
        return price;
    }

    public void setPrice(Integer price) {
        this.price = price;
    }

    public Integer getOriginalPrice() {
        return originalPrice;
    }

    public void setOriginalPrice(Integer originalPrice) {
        this.originalPrice = originalPrice;
    }
}
