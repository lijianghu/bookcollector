# audit 模块知识

> 包路径：`com.bookcollector.audit` · 最后更新：2026-09-23

## 1. 这个模块是干什么的

每打完一次微信读书接口就落一条记录 —— **成功失败都记**。

这张表是排查「某页为什么没数据」的**第一现场**：能回答「到底发了几次请求、每次什么状态码、
返回几条、耗时多少」。所以列表默认按时间倒序，且支持 `onlyFailed=true` 一键过滤异常请求。

## 2. 类清单

| 类 | 角色 | 一句话职责 |
|---|---|---|
| `controller/RequestAuditController` | Controller | 1 个端点（分页查询），**极薄转发** |
| `service/AuditService` | Service 接口 | 1 个方法（分页） |
| `service/impl/AuditServiceImpl` | Service 实现 | 参数归一化 + 转发 |
| `repository/ApiRequestRepository` | Repository | **写入**：`recordPage`，只插不改，**失败不抛异常** |
| `repository/ApiRequestQueryRepository` | Repository | **查询**：条件拼装 + 排序 |
| `entity/ApiRequest` | Entity | 15 个字段（runId / 目标 / url / 状态码 / 耗时 / 结果数 / 错误摘要） |

> 写与查**刻意分成两个类**，见铁律 1。

## 3. 调用关系（图谱）

```
collector.CollectLoop ──► ApiRequestRepository.recordPage()  ──► api_requests
                              （只插不改，失败吞掉不抛）

RequestAuditController ──► AuditService(Impl) ──► ApiRequestQueryRepository ──► api_requests
                                                        （条件拼装 + 排序）
```

## 4. 数据集合与索引

| 集合 | 索引 | 为什么 |
|---|---|---|
| `api_requests` | `idx_createdAt`（DESC） | 「最近有哪些请求失败」——按时间倒序翻 |
| | `idx_runId` | 「某次运行打了哪些请求」 |

> 写入量大（21 分类 × 数百页），索引克制，只建真正会查的两个。

## 5. 对外接口

| 方法 | 路由 | 说明 |
|---|---|---|
| GET | `/api/requests` | 分页。参数：`runId` / `targetType` / `targetId` / `onlyFailed` / `page` / `size` |

- 排序固定：`createdAt` **倒序** + `_id` 升序兜底
- 分页归一化：`page < 1 → 1`、`size` 默认 20、上限 200
- `onlyFailed=true` 的判据是**或**关系：`statusCode != 200` **或** `errorMsg` 非空

## 6. 不变量与铁律 ← 改这个模块前必读

1. **写入与查询分成两个类，不要合并。**
   写入那个类的约束是「**只插不改，且失败不抛异常**」（审计不能影响采集主流程）；
   查询的约束**完全相反** —— 它要能被上层的异常处理器正常兜住，失败了应该报错。
   两者的「错误处理哲学」不同，混在一个类里迟早有人把 `try-catch 吞掉` 复制到查询方法上。

2. **审计失败绝不抛异常。** `recordPage` 内部吞掉异常并打日志。
   为什么：审计是旁路，它不该让一次采集失败。

3. **`onlyFailed` 的判据必须是「或」。**
   有些失败是「HTTP 200 但 body 里没有 `books` 数组」，只看状态码会漏掉它们。

4. **只有查询，没有删除。**
   审计记录的价值恰恰在于「事后还在」。提供一个「清空审计」按钮，
   早晚会有人手滑点掉正在排查的那批数据。要清理请直接连库操作。

5. **失败原因的措辞来自 `WereadRawResponse.describeFailure()`**（`collector` 模块），
   本模块只负责存。用户在「任务失败原因」和「请求审计」里看到的必须是同一句话。

6. **`statusCode = -1` 表示「请求在客户端就失败了」**（网络异常等），
   不是「服务端回了 -1」。⚠️ 但要注意 `collector` 模块第 9.1 节那个陷阱：
   修复前，服务端明确的 4xx/5xx 也会被错记成 `-1`。

## 7. 依赖与耦合

- **依赖**：`common.{PageResult,ResultBean}`、`util.PageQueryUtil`、`collector.dto.{WereadPage,WereadRawResponse}`。
- **被依赖**：`collector.CollectLoop`（写）、`stats`（数 `requestTotal`）。
- ⚠️ `audit ← collector.dto` 与 `collector → audit.repository` 构成**已知的环**，
  设计层面耦合，本轮不解决。

## 8. 测试与验收

`CollectEngineIT` / `TaskControlIT` 运行时会在 `api_requests` 里留下真实记录，
可用 `pymongo` 直接核对条数与状态码。

```bash
tools/mvn8.sh -f backend/pom.xml -o -B test -Dtest=CollectEngineIT
```

## 9. 已知陷阱（现象 → 根因 → 修法）

### 9.1 审计页把 HTTP 500 显示成 `-1`

- **现象**：一条明显是服务端错误的请求，`statusCode` 是 `-1`，
  `errorMsg` 是「网络异常 InternalServerError」。
- **根因**：`RestTemplate` 的默认错误处理器在 4xx/5xx 上抛异常，
  异常在 `WereadClient.fetchRaw` 里被包成 `error`，`statusCode` 落成 `-1`。
- **修法**：见 `collector` 模块第 9.1 节 —— `RestTemplateConfig.RawResponseErrorHandler`。
  **本模块不需要改**，但排查这类现象时要往上游看。

### 9.2 `api_requests` 增长很快

- **现象**：跑一轮全量采集后表里多了几百条。
- **根因**：设计如此 —— 每页一条。
- **修法**：**不要**加「只记失败」的开关。成功的记录同样有价值
  （「这一页返回了几条」是判断上游是否改过接口的唯一依据）。
  要清理请手工连库。
