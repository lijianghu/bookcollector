package com.bookcollector.config;

import com.bookcollector.common.enums.TargetType;
import com.bookcollector.setting.entity.Setting;
import com.bookcollector.taxonomy.entity.TaxonomyItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动时初始化字典数据。
 *
 * <h3>数据来源</h3>
 * 21 个分类 + 7 个榜单，<b>逐字抄自原 {@code config.py}</b> 的
 * {@code CATEGORIES} / {@code RANKING_LISTS}，顺序也保持一致（决定前端下拉框顺序）。
 *
 * <h3>幂等策略：只补缺失，不覆盖已有</h3>
 * 不是 upsert，而是「先查已有 key，再只插没有的」。这么做是因为：
 * <ul>
 *   <li>用户在界面上把某个分类改成 {@code enabled=false}（停用）后，
 *       重启服务<b>不应该</b>被重置回启用状态 —— upsert 就会踩这个坑</li>
 *   <li>字典是低频变更数据，代码里改名字后需要生效的场景几乎不存在；
 *       真发生了，手工改库比「每次启动覆盖用户设置」更可控</li>
 * </ul>
 *
 * <p>执行顺序排在 {@link MongoIndexInitializer} 之后，保证 {@code uk_type_code}
 * 唯一索引已经就位 —— 否则并发/重复启动时可能插出重复字典。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    /**
     * 21 个分类。抄自 config.py 的 {@code CATEGORIES}。
     * 注意 1900001 / 2000001 不是笔误 —— 原文件里就是这两个不规则编号。
     */
    private static final String[][] CATEGORIES = {
            {"300000", "文学"},
            {"100000", "精品小说"},
            {"200000", "历史"},
            {"400000", "艺术"},
            {"500000", "人物传记"},
            {"600000", "哲学宗教"},
            {"700000", "计算机"},
            {"800000", "心理"},
            {"900000", "社会文化"},
            {"1000000", "个人成长"},
            {"1100000", "经济理财"},
            {"1200000", "政治军事"},
            {"1300000", "童书"},
            {"1400000", "教育学习"},
            {"1500000", "科学技术"},
            {"1600000", "生活百科"},
            {"1700000", "期刊杂志"},
            {"1800000", "原版书"},
            {"1900001", "男生小说"},
            {"2000001", "女生小说"},
            {"2100000", "医学健康"}
    };

    /** 7 个榜单。抄自 config.py 的 {@code RANKING_LISTS}。 */
    private static final String[][] RANKINGS = {
            {"rising", "飙升榜"},
            {"hot_search", "热搜榜"},
            {"newbook", "新书榜"},
            {"general_novel_rising", "小说飙升榜"},
            {"all", "总榜"},
            {"newrating_publish", "神作榜"},
            {"newrating_potential_publish", "潜力榜"}
    };

    /**
     * 系统配置默认值。抄自 config.py 的 {@code COLLECTION_CONFIG.default_max_pages}
     * 与 {@code API_CONFIG.delay_between_requests}。
     */
    private static final String[][] SETTING_DEFAULTS = {
            {"defaultMaxPages", "10", "新建采集任务时的默认最大页数"},
            {"requestDelayMs", "1000", "每页请求之间的间隔（毫秒）"}
    };

    private final MongoTemplate mongoTemplate;

    public SeedRunner(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("========== 开始初始化字典数据 ==========");

        SeedStat catStat = seedTaxonomy(TargetType.CATEGORY, CATEGORIES);
        SeedStat rankStat = seedTaxonomy(TargetType.RANKING, RANKINGS);
        SeedStat setStat = seedSettings();

        log.info("字典初始化完成：");
        log.info("  分类 taxonomy_config(CATEGORY)  ：新增 {} 条，已存在 {} 条，共 {} 条",
                catStat.inserted, catStat.skipped, catStat.total());
        log.info("  榜单 taxonomy_config(RANKING)   ：新增 {} 条，已存在 {} 条，共 {} 条",
                rankStat.inserted, rankStat.skipped, rankStat.total());
        log.info("  配置 settings                   ：新增 {} 条，已存在 {} 条",
                setStat.inserted, setStat.skipped);

        int expect = CATEGORIES.length + RANKINGS.length;
        long actual = mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(),
                TaxonomyItem.class);
        if (actual != expect) {
            log.warn("⚠️ taxonomy_config 实际条数 {} 与预期 {} 不一致，请检查是否有残留脏数据", actual, expect);
        }
        log.info("========== 字典数据初始化结束 ==========");
    }

    /** 按 (type, code) 补齐缺失的字典项，已有的一律不动 */
    private SeedStat seedTaxonomy(TargetType type, String[][] rows) {
        Set<String> existing = new HashSet<String>();
        List<TaxonomyItem> all = mongoTemplate.findAll(TaxonomyItem.class);
        for (TaxonomyItem item : all) {
            if (item.getType() != null && item.getCode() != null) {
                existing.add(item.getType() + "|" + item.getCode());
            }
        }

        List<TaxonomyItem> toInsert = new ArrayList<TaxonomyItem>();
        int sort = 1;
        for (String[] row : rows) {
            String key = type.name() + "|" + row[0];
            if (existing.contains(key)) {
                sort++;
                continue;
            }
            toInsert.add(TaxonomyItem.builder()
                    .type(type.name())
                    .code(row[0])
                    .name(row[1])
                    .enabled(Boolean.TRUE)
                    .sort(sort)
                    .build());
            sort++;
        }

        if (!toInsert.isEmpty()) {
            mongoTemplate.insertAll(toInsert);
        }
        return new SeedStat(toInsert.size(), rows.length - toInsert.size());
    }

    /** 补齐缺失的配置项，已有的一律不动（用户改过的值不能被重启冲掉） */
    private SeedStat seedSettings() {
        Set<String> existing = new HashSet<String>();
        for (Setting s : mongoTemplate.findAll(Setting.class)) {
            if (s.getKey() != null) {
                existing.add(s.getKey());
            }
        }

        List<Setting> toInsert = new ArrayList<Setting>();
        for (String[] row : SETTING_DEFAULTS) {
            if (existing.contains(row[0])) {
                continue;
            }
            toInsert.add(Setting.builder()
                    .key(row[0])
                    .value(row[1])
                    .remark(row[2])
                    .updatedAt(new Date())
                    .build());
        }

        if (!toInsert.isEmpty()) {
            mongoTemplate.insertAll(toInsert);
        }
        return new SeedStat(toInsert.size(), SETTING_DEFAULTS.length - toInsert.size());
    }

    /** 小工具：记录一次 seed 的新增/跳过数量 */
    private static final class SeedStat {
        final int inserted;
        final int skipped;

        SeedStat(int inserted, int skipped) {
            this.inserted = inserted;
            this.skipped = skipped;
        }

        int total() {
            return inserted + skipped;
        }
    }
}
