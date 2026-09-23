package com.bookcollector.cursor.service.impl;

import com.bookcollector.common.BizException;
import com.bookcollector.common.ResultBean;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.cursor.entity.CollectCursor;
import com.bookcollector.cursor.repository.CursorStore;
import com.bookcollector.cursor.req.CursorSetRequest;
import com.bookcollector.cursor.service.CursorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CursorService} 的实现。
 *
 * <p>两条业务规则的由来（「重置不等于清库」、「运行中重置无效」）
 * 见 {@link CursorService} 的类注释。
 */
@Service
public class CursorServiceImpl implements CursorService {

    private static final Logger log = LoggerFactory.getLogger(CursorServiceImpl.class);

    /** 重置后返回给前端的提示语，避免用户以为重置会把书删掉 */
    private static final String RESET_NOTE =
            "已采到的图书不会被删除；重置后再跑任务会从该位置继续（重复采到的书按 bookId 幂等更新）";

    private final CursorStore cursorStore;

    public CursorServiceImpl(CursorStore cursorStore) {
        this.cursorStore = cursorStore;
    }

    @Override
    public List<CollectCursor> list() {
        return cursorStore.findAll();
    }

    @Override
    public Map<String, Object> set(CursorSetRequest req) {
        TargetType type;
        try {
            type = TargetType.of(req.getTargetType());
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultBean.code_warn, e.getMessage());
        }
        String targetId = req.getTargetId() == null ? "" : req.getTargetId().trim();
        if (targetId.isEmpty()) {
            throw new BizException(ResultBean.code_warn, "targetId 不能为空");
        }

        int maxIndex = req.getMaxIndex() == null ? 0 : req.getMaxIndex();
        if (maxIndex < 0) {
            throw new BizException(ResultBean.code_warn, "maxIndex 不能为负数");
        }

        boolean existed = cursorStore.load(type, targetId) != null;
        cursorStore.setCursor(type, targetId, req.getTargetName(), maxIndex);

        CollectCursor after = cursorStore.load(type, targetId);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("targetType", type.name());
        data.put("targetId", targetId);
        data.put("maxIndex", after == null ? maxIndex : after.getMaxIndex());
        data.put("created", !existed);
        data.put("reset", maxIndex == 0);
        // 说清楚「重置不等于清库」，避免用户以为重置会把书删掉
        data.put("note", RESET_NOTE);
        return data;
    }

    @Override
    public Map<String, Object> delete(String id) {
        CollectCursor cursor = cursorStore.findById(id);
        if (cursor == null) {
            throw BizException.notFound("游标不存在：" + id);
        }
        TargetType type = TargetType.of(cursor.getTargetType());
        cursorStore.delete(type, cursor.getTargetId());

        log.info("已删除游标：{}/{}", cursor.getTargetType(), cursor.getTargetId());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", id);
        data.put("targetType", cursor.getTargetType());
        data.put("targetId", cursor.getTargetId());
        data.put("deleted", 1);
        return data;
    }
}
