# book 模块知识

> 包路径：`com.bookcollector.book` · 最后更新：2026-09-23

## 1. 这个模块是干什么的

图书库的可视化读写：分页筛选、详情、编辑、单条/批量删除。
数据本身由 `collector` 采集写入，本模块**不负责采集**，只负责「人怎么看和怎么改」。

## 2. 类清单

| 类 | 角色 | 一句话职责 |
|---|---|---|
| `controller/BookController` | Controller | 5 个端点，**极薄转发** |
| `service/BookService` | Service 接口 | 5 个方法（分页/详情/编辑/删除/批量删除） |
| `service/impl/BookServiceImpl` | Service 实现 | 三条业务规则（404 / 白名单组装 / 删前确认存在） |
| `repository/BookRepository` | Repository | Mongo 访问：分页、upsert、局部更新、批量删 |
| `req/BookQuery` | req | 12 个筛选条件 + 排序白名单 + 分页归一化 |
| `req/BookUpdateRequest` | req | 编辑请求体（**刻意不含不可改字段**） |
| `req/BatchDeleteRequest` | req | 批量删除请求体 |
| `entity/Book` | Entity | 46 个业务字段 + 4 个扩展字段 + 3 个采集元数据；3 个嵌套内部类 |

**几个容易忘的实现细节**：

- **`Book` 的结构**：`BookCategory` / `RatingDetail` / `MaxFreeInfo` 三个**嵌套内部类**
  （都 `implements Serializable`）。改字段时注意 `RatingDetail` 里是 `newRating`
  （0~1000 的推荐值，**原样存**，展示层自己除 10）—— 别在实体上做换算。
- **`upsertMany` 用 `bulkOps(BulkMode.UNORDERED)`**：一次网络往返代替上千次。
  **`UNORDERED`** 是有意的 —— 单条失败不该中断整批。
- **`upsertMany` 的 Update 是手搓的原始 `Document`**（不是 `Update.set(...)` 链）：
  因为它要同时下发 **`$set` + `$setOnInsert` + `$addToSet`** 三个操作符，而 `$set` 的内容是
  「实体全字段」—— 逐个字段手写要 50 行且必然漏字段。
  - 🔴 **`_id` 绝对不能出现在 `$set` 里** —— MongoDB 会直接报
    `Performing an update on the path '_id' would modify the immutable field '_id'`。
  - `firstCollectedAt` → **`$setOnInsert`**（「第一次采到的时间」永不被覆盖）
  - `collectSource` → **`$addToSet`**（多来源累加，且不重复）
- **`IN_QUERY_CHUNK = 1000`**：`findByBookIds` 和批量删除都按 1000 分片下发 `$in`，
  避免单次 `$in` 过大。新增任何「按一批 id 查/删」的方法都要照做。
- `upsertMany` 会**跳过没有 `bookId` 的记录**并打 warn（不是抛异常）——
  采集侧偶尔会有脏节点，不该因此整批失败。

## 3. 调用关系（图谱）

```
BookController ──► BookService(Impl) ──► BookRepository ──► books
                                              ▲
                                              │ upsertMany（批量 upsert）
                                              │
                                        collector.BookWriter   ← 采集侧唯一的写入方
```

> `BookRepository` 被两个方向使用：**读/改**（本模块）和**写**（`collector.BookWriter`）。
> 改它的 upsert 方法前先看 `collector` 模块的第 6 节铁律 1（并集语义）。

## 4. 数据集合与索引

| 集合 | 索引 | 为什么 |
|---|---|---|
| `books` | `uk_bookId` **唯一** | 🔴 **幂等 upsert 的正确性依赖**，不是性能优化 |
| | `idx_categories` (`categories.categoryId`) | 按分类筛 |
| | `idx_lastCollectedAt` | 「最近采过什么」 |
| | `idx_newRating` | 按评分筛/排序 |
| | `idx_firstCollectedAt` | 「每天新增了多少」（口径见 `stats`） |

> `idx_lastCollectedAt` 与 `idx_firstCollectedAt` **不是重复索引**：前者服务
> 「最近采过什么」，后者服务「每天新增了多少」（`lastCollectedAt` 每次 upsert 都刷新，
> 重采会虚增，所以统计口径必须用 `firstCollectedAt`）。

## 5. 对外接口

| 方法 | 路由 | 说明 |
|---|---|---|
| GET | `/api/books` | 分页筛选。参数对象直接绑 `BookQuery`（12 个查询条件） |
| GET | `/api/books/{bookId}` | 详情（46 业务字段 + 采集元数据） |
| PUT | `/api/books/{bookId}` | 编辑（**局部更新**，只写请求体里出现的字段） |
| DELETE | `/api/books/{bookId}` | 单条删除。⚠️ 下次采集到会被重新写入（**删除不是永久拉黑**） |
| POST | `/api/books/batch-delete` | 批量删除，返回 `requested` + `deleted`（**两者可能不同**） |

**筛选条件**：`categoryId` / `minRating` / `maxRating` / `minReadingCount` /
`targetType` + `targetId`（采集来源）/ `author` / `minPublishTime` / `maxPublishTime`。

**排序字段白名单**（`BookQuery#resolveSortPath()`）：`newRating`（默认）/ `readingCount` /
`newRatingCount` / `lastCollectedAt` / `publishTime`。

## 6. 不变量与铁律 ← 改这个模块前必读

1. **排序字段必须走白名单。** `resolveSortPath()` 把前端传的字符串映射成固定的 Mongo 路径，
   不合法的一律回退 `newRating`。**绝不能**把用户传的字符串直接拼进 Mongo 路径 —— 那是注入面。

2. **🔴 `newRating` 的路径是顶层 `newRating`，不是 `newRatingDetail.newRating`。**
   `newRatingDetail` 的实际结构是 `{good, fair, poor, recent, title}`，里面**没有** `newRating`。
   后果是**静默的**：排序指向不存在的字段不报错（只是「排了跟没排一样」），
   而筛选会**永远返回 0 条**，页面不报错只是空列表。
   这个坑在 S1/S2 的测试里照不出来（那些测试验的是「解析对不对」「索引建没建」），
   S4 做仪表盘评分分布时发现柱图全是 0 才牵出来。

3. **编辑一律「只写传了的字段」。** `BookServiceImpl.update` 用
   `Map<String,Object> sets` + `putIfNotNull` 逐字段 `Update.set`，**绝不**用整本 `save()`。
   为什么：整本 `save()` 要求前端把 46 个字段一个不落地回传，少传一个那个字段就被写成 null
   —— 这是「隐式清空」，是最难发现的一类数据损坏。
   一个字段都没传 → 报 `code=warn`「没有需要修改的字段」，不要静默成功。

4. **不可改字段用「字段缺失」在类型层面挡住，不靠运行时 if。**
   `BookUpdateRequest` 里干脆没有 `bookId` / `searchIdx` / `collectSource` / `firstCollectedAt`。
   `collectSource` 与 `firstCollectedAt` 是采集元数据，改了会破坏来源统计与新增口径。

5. **批量删除返回「实际删除条数」，不是请求条数。**
   前端列表是上一次查询的快照，期间某本书可能已被另一个标签页删掉。
   报「实际值」比报「请求值」诚实。

6. **`maxPublishTime` 会把粗粒度输入补齐。** `"2023"` → `"2023-12-31 23:59:59"`，
   `"2023-06"` → `"2023-06-31 23:59:59"`。为什么：`publishTime` 是定长字符串，
   做的是**字符串**比较；不补齐的话 `"2023"` 做 `$lte` 会把 `"2023-08-01 00:00:00"` 排除掉。
   `2023-06-31` 是不存在的日期，但没关系 —— 它只起「该月最大值」的作用，不会被解析成日期。

7. **不做模糊搜索。** `author` 是**精确匹配**（`Criteria.is`），刻意不支持正则。

## 7. 依赖与耦合

- **依赖**：`common.{PageResult,BizException,ResultBean}`、`util.PageQueryUtil`（分页样板）。
- **被依赖**：`collector.BookWriter`（写）、`stats`（聚合统计 `books`）。
- 分页样板（count 短路 / 排序 / skip+limit）统一走 `PageQueryUtil`，本模块只提供
  「条件怎么拼」（`buildCriteria`）和「按什么排」。

## 8. 测试与验收

| 测试 | 覆盖 |
|---|---|
| `S1DataLayerTest` | 数据层：upsert 幂等、索引存在性 |
| `BookQuerySemanticsIT` | **筛选/排序语义**（含 `newRating` 路径回归）、`StatsService` 聚合 |
| `ApiCrudIT` | 端到端 CRUD 契约 |

```bash
tools/mvn8.sh -f backend/pom.xml -o -B test -Dtest=BookQuerySemanticsIT
```

## 9. 已知陷阱（现象 → 根因 → 修法）

### 9.1 筛选永远返回 0 条，且不报错

- **现象**：按评分筛选（`minRating=800`）永远空列表；按评分排序看不出效果。
- **根因**：`Criteria.where("newRatingDetail.newRating")` —— 这个字段不存在。
  `$match` 匹配不到任何文档时**不抛异常**，只是返回空。
- **修法**：路径改回顶层 `newRating`。**三处都要改**：`BookRepository.buildCriteria`、
  `BookQuery.resolveSortPath()`、`MongoIndexInitializer` 的 `idx_newRating`
  （索引已建在错字段上时，靠 `dropIndexIfFieldChanged` 在启动时自愈）。

### 9.2 删除图书后又「复活」了

- **现象**：删掉一本书，下次采集它又回来了。
- **根因**：这是**设计使然** —— `books` 是「历史累计的并集」，删除不是永久拉黑。
- **修法**：不是缺陷。接口的 `@Operation(description = ...)` 里已明确写了这一点，
  前端提示也要说清楚。
