package com.bookcollector.taxonomy;

import com.bookcollector.common.BizException;
import com.bookcollector.common.ResultBean;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.cursor.CursorStore;
import com.bookcollector.task.TaskRepository;
import com.bookcollector.taxonomy.dto.TaxonomyRequest;
import com.bookcollector.taxonomy.entity.TaxonomyItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分类 / 榜单字典的业务逻辑（FR-C1 / FR-C2 / FR-C3）。
 *
 * <h3>🔴 三条业务规则，都不是可有可无的</h3>
 * <ol>
 *   <li><b>编辑不允许改 {@code code}</b>。{@code code} 是采集目标的身份
 *       （{@code collect_tasks.targetId} 和 {@code collect_cursors.targetId} 都存它）。
 *       改了 code 之后，已有的任务和游标就指向一个字典里不存在的目标 ——
 *       任务还能跑（因为 URL 是拼 code 的），但「新建任务」的下拉框里找不到它，
 *       界面上表现为「这个任务的目标名点不进去」。改名（{@code name}）无所谓，
 *       因为 name 只是展示，而且是冗余快照。</li>
 *   <li><b>有任务引用则不允许删除</b>。见 {@link #delete}。</li>
 *   <li><b>删除时顺带清理游标</b>。否则 {@code collect_cursors} 里会留下
 *       一条永远没有入口能看到的孤儿记录（「断点续传」页是按游标列表渲染的，
 *       它会出现，但点「重置」也没有任何意义）。</li>
 * </ol>
 *
 * <p>seed 逻辑（FR-C3）在 {@code SeedRunner} 里，不在这里 —— 它只在启动时跑一次，
 * 且策略是「只补缺失、不覆盖已有」，与这里「用户主动增删改」的语义不同。
 */
@Service
public class TaxonomyService {

    private static final Logger log = LoggerFactory.getLogger(TaxonomyService.class);

    private final TaxonomyRepository repository;
    private final TaskRepository taskRepository;
    private final CursorStore cursorStore;

    public TaxonomyService(TaxonomyRepository repository,
                           TaskRepository taskRepository,
                           CursorStore cursorStore) {
        this.repository = repository;
        this.taskRepository = taskRepository;
        this.cursorStore = cursorStore;
    }

    /** 列表。{@code enabledOnly=true} 用于「新建任务」的目标下拉框 */
    public List<TaxonomyItem> list(TargetType type, Boolean enabledOnly) {
        return repository.findByType(type, enabledOnly);
    }

    /**
     * 新增。
     *
     * <p>{@code sort} 不传时自动排到当前类型的末尾（{@code maxSort + 1}）。
     * 让用户自己填 sort 是没必要的负担，而默认都填 1 又会让下拉框顺序随机。
     */
    public TaxonomyItem create(TargetType type, TaxonomyRequest req) {
        String code = trimToNull(req.getCode());
        if (code == null) {
            throw new BizException(ResultBean.code_warn, "code 不能为空");
        }
        String name = trimToNull(req.getName());
        if (name == null) {
            throw new BizException(ResultBean.code_warn, "name 不能为空");
        }

        TaxonomyItem item = TaxonomyItem.builder()
                .type(type.name())
                .code(code)
                .name(name)
                .enabled(req.getEnabled() == null ? Boolean.TRUE : req.getEnabled())
                .sort(req.getSort() == null ? repository.maxSort(type) + 1 : req.getSort())
                .remark(req.getRemark())
                .build();

        try {
            TaxonomyItem saved = repository.save(item);
            log.info("新增字典项：{}/{} {}", type, code, name);
            return saved;
        } catch (DuplicateKeyException e) {
            // uk_type_code 唯一索引。这是产品缺陷之外的正常路径 —— 用户重复添加同一个目标
            throw BizException.duplicate("该" + type.getLabel() + "已存在：code=" + code);
        }
    }

    /**
     * 编辑。只能改 {@code name} / {@code enabled} / {@code sort} / {@code remark}。
     *
     * <p>请求体里带了 {@code code} 且与当前值不同 → 直接报错，
     * 而不是「静默忽略」。静默忽略会让用户以为改成功了（这正是最难排查的一类问题）。
     */
    public TaxonomyItem update(String id, TaxonomyRequest req) {
        TaxonomyItem item = requireById(id);

        String newCode = trimToNull(req.getCode());
        if (newCode != null && !newCode.equals(item.getCode())) {
            throw new BizException(ResultBean.code_warn,
                    "不允许修改 code（它是采集目标的身份，已有任务与游标都指向它）。"
                            + "如需换目标，请新建一条字典项");
        }

        String name = trimToNull(req.getName());
        if (name != null) {
            item.setName(name);
        }
        if (req.getEnabled() != null) {
            item.setEnabled(req.getEnabled());
        }
        if (req.getSort() != null) {
            item.setSort(req.getSort());
        }
        if (req.getRemark() != null) {
            item.setRemark(req.getRemark());
        }
        return repository.save(item);
    }

    /**
     * 删除。
     *
     * <p><b>有任务引用 → 拒绝。</b> 一个任务就是「某个目标 + 最大页数」，
     * 删掉字典项后那个任务依然会跑（URL 是按 code 拼的），但它的目标
     * 在字典里查不到了 —— 界面上会出现「新建任务时选不到它，但任务列表里它还在」。
     * 这种不一致比直接拒绝难排查得多，所以这里选择拒绝，并告诉用户还剩几个任务。
     *
     * <p><b>只有游标、没有任务 → 允许删，并顺带清掉游标。</b>
     * 游标是采集过程的副产品，不是用户资产；留一条孤儿游标只会在
     * 「断点续传」页上多一行没有意义的记录。
     *
     * @return 顺带清理的游标条数（0 或 1）
     */
    public int delete(String id) {
        TaxonomyItem item = requireById(id);
        TargetType type = parseType(item.getType());

        long refs = taskRepository.countByTarget(item.getType(), item.getCode());
        if (refs > 0) {
            throw new BizException(ResultBean.code_warn,
                    "该" + type.getLabel() + "「" + item.getName() + "」还有 " + refs
                            + " 个采集任务在引用它，请先删除这些任务");
        }

        int cursorsRemoved = 0;
        if (cursorStore.load(type, item.getCode()) != null) {
            cursorStore.delete(type, item.getCode());
            cursorsRemoved = 1;
            log.info("删除字典项时顺带清理了游标：{}/{}", type, item.getCode());
        }

        repository.deleteById(id);
        log.info("已删除字典项：{}/{} {}", type, item.getCode(), item.getName());
        return cursorsRemoved;
    }

    private TaxonomyItem requireById(String id) {
        TaxonomyItem item = repository.findById(id);
        if (item == null) {
            throw BizException.notFound("字典项不存在：" + id);
        }
        return item;
    }

    /** 库里存的 type 是字符串，可能是历史脏数据，解析失败要报可读错误而不是 500 */
    private TargetType parseType(String type) {
        try {
            return TargetType.of(type);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultBean.code_err, "字典项的类型非法：" + type);
        }
    }

    private String trimToNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return s.trim();
    }
}
