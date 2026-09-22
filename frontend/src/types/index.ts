/**
 * 后端数据结构的手写 TS 类型。
 *
 * 为什么不自动生成：本期后端只有 8 个实体 + 十来个 DTO，手写一遍顺便能核对字段名。
 * 自动生成（openapi-typescript）需要额外工具链，而且生成出来的名字是 `Book1`/`Book2`
 * 这种没法用的东西。
 *
 * ⚠️ 两条必须知道的序列化事实（已用 curl 实测确认）：
 *
 * 1. **日期是 UTC 的 ISO-8601 字符串**，形如 `"2026-09-21T11:39:48.584+00:00"`。
 *    Spring Boot 默认关掉了 `WRITE_DATES_AS_TIMESTAMPS`，所以不是毫秒数。
 *    直接 `new Date(iso)` 会被浏览器按 UTC 解析、再按本地时区渲染 —— 这是对的。
 *    但**千万不要**用 `iso.slice(0, 19).replace('T', ' ')` 这种土办法，
 *    那样会把 UTC 时间当成本地时间显示，中国时区下会差 8 小时。
 *
 * 2. **`null` 会被原样输出**（`"remark": null`），不是省略。所以可选字段要写 `?`
 *    还是 `| null` 取决于后端：实体字段没有 `@JsonInclude(NON_NULL)`，
 *    所以「有 key 但值为 null」是常态 → 这里统一用 `?: T | null` 表达。
 */

// ======================================================================
// 通用返回结构（对应 com.bookcollector.common.ResultBean / PageResult）
// ======================================================================

/** 统一返回体。⚠️ 成功码是 200，不是 0 */
export interface ResultBean<T = unknown> {
  code: number
  message: string
  traceId: string
  data: T
}

/** 分页结构。注意后端还会多带一个 `totalPages` */
export interface PageResult<T = unknown> {
  list: T[]
  total: number
  page: number
  size: number
  totalPages: number
}

/** 业务错误码常量，与 Java 的 ResultBean 保持一致 */
export const CODE_OK = 200
export const CODE_WARN = 400
export const CODE_NOTFOUND = 404
export const CODE_DUPLICATE_KEY = 405
export const CODE_ERR = 500
export const CODE_SESSION_INVALID = 4100

// ======================================================================
// 枚举（后端是 String，取值是枚举名）
// ======================================================================

/** 采集目标类型 */
export type TargetType = 'CATEGORY' | 'RANKING'

export const TARGET_TYPE_LABEL: Record<TargetType, string> = {
  CATEGORY: '分类',
  RANKING: '榜单',
}

/**
 * 任务 / 运行状态。
 *
 * ⚠️ `INTERRUPTED` **不是**终态 —— 它表示「进程被杀了但游标还在」，可以直接恢复。
 * 只有 SUCCESS / FAILED / CANCELED 是终态。
 */
export type TaskStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'PAUSED'
  | 'SUCCESS'
  | 'FAILED'
  | 'CANCELED'
  | 'INTERRUPTED'

export const TASK_STATUS_LABEL: Record<TaskStatus, string> = {
  PENDING: '待执行',
  RUNNING: '运行中',
  PAUSED: '已暂停',
  SUCCESS: '已完成',
  FAILED: '失败',
  CANCELED: '已取消',
  INTERRUPTED: '已中断',
}

/** Element Plus 的 tag 类型 */
export type TagType = 'success' | 'warning' | 'info' | 'primary' | 'danger'

export const TASK_STATUS_TAG: Record<TaskStatus, TagType> = {
  PENDING: 'info',
  RUNNING: 'primary',
  PAUSED: 'warning',
  SUCCESS: 'success',
  FAILED: 'danger',
  CANCELED: 'info',
  INTERRUPTED: 'warning',
}

/** 终态：不可再推进 */
export function isTerminalStatus(status?: TaskStatus | null): boolean {
  return status === 'SUCCESS' || status === 'FAILED' || status === 'CANCELED'
}

/** 活跃态：正在占用采集线程 */
export function isActiveStatus(status?: TaskStatus | null): boolean {
  return status === 'RUNNING' || status === 'PAUSED'
}

/** 可被 start / resume 拉起 */
export function isResumableStatus(status?: TaskStatus | null): boolean {
  return status === 'PENDING' || status === 'PAUSED' || status === 'INTERRUPTED'
}

// ======================================================================
// 认证（com.bookcollector.auth）
// ======================================================================

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginData {
  token: string
  username: string
}

export interface MeData {
  username: string
  nickname: string
  roles: string[]
}

// ======================================================================
// 图书（com.bookcollector.book.entity.Book）
// ======================================================================

/** 平台分类（`categories` 数组元素） */
export interface BookCategory {
  categoryId?: number | null
  subCategoryId?: number | null
  categoryType?: number | null
  title?: string | null
}

/**
 * 评分详情。
 *
 * 🔴 **这里没有 `newRating` 字段** —— 推荐值（0~1000）在 `Book` 的**顶层** `newRating` 上。
 * 这是 S4 照出来的一个真实缺陷：一开始把评分当成 `newRatingDetail.newRating` 读，
 * 结果排序、筛选、评分分布图全部静默失效（字段不存在 → 查询匹配不到 → 返回空，
 * 而且不报错）。见 `docs/02` 的「缺陷 1」。
 */
export interface RatingDetail {
  good?: number | null
  fair?: number | null
  poor?: number | null
  recent?: number | null
  title?: string | null
}

export interface MaxFreeInfo {
  maxFreeChapterIdx?: number | null
  maxFreeChapterUid?: number | null
  maxFreeChapterRatio?: number | null
}

/** 图书。46 个字段，与后端实体一一对应 */
export interface Book {
  /** MongoDB 主键 */
  id?: string | null
  bookId: string
  title?: string | null
  author?: string | null
  translator?: string | null
  cover?: string | null
  version?: number | null
  format?: string | null
  type?: number | null
  price?: number | null
  originalPrice?: number | null
  soldout?: number | null
  bookStatus?: number | null
  payingStatus?: number | null
  payType?: number | null
  intro?: string | null
  centPrice?: number | null
  finished?: number | null
  maxFreeChapter?: number | null
  free?: number | null
  mcardDiscount?: number | null
  ispub?: number | null
  extraType?: number | null
  cpid?: number | null
  /** 字符串，形如 "2022-08-01 00:00:00"；库里存在只有年份的脏值 "2022" */
  publishTime?: string | null
  /** 平台分类名，形如「精品小说-社会小说」 */
  category?: string | null
  categories?: BookCategory[] | null
  hasLecture?: number | null
  lastChapterIdx?: number | null
  paperBookSkuId?: string | null
  blockSaveImg?: number | null
  language?: string | null
  isTraditionalChinese?: boolean | null
  hideUpdateTime?: boolean | null
  isEpubComics?: number | null
  isVerticalLayout?: number | null
  isShowTts?: number | null
  webBookControl?: number | null
  selfProduceIncentive?: boolean | null
  isAutoDownload?: number | null
  /** 🔴 推荐值 0~1000，**顶层字段** */
  newRating?: number | null
  newRatingCount?: number | null
  newRatingTitle?: string | null
  searchIdx?: number | null
  typeInfo?: number | null
  readingCount?: number | null
  newRatingDetail?: RatingDetail | null
  maxFreeInfo?: MaxFreeInfo | null
  copyrightChapterUids?: number[] | null
  /**
   * 🔴 注意是小写 `lpush`，不是 `lPush`。
   *
   * Java 实体里字段名是 `lPushName`（`Book.java:210`），但 Jackson 的默认命名策略
   * 会把 `getLPushName()` 序列化成 **`lpushName`** —— 它把开头连续的大写字母
   * 一起小写了（标准 JavaBeans 的 decapitalize 规则是「前两个字母都大写则原样保留」，
   * Jackson 默认**不用**这条规则）。
   *
   * 所以前端要是按 Java 的拼写写成 `lPushName`，读出来永远是 `undefined`，
   * 详情页那一栏永远显示 `-` —— 不报错、不提示，纯静默。
   * S6.2 做详情抽屉时用「拿真实接口返回的 54 个键和 TS 声明逐个比对」才发现。
   */
  lpushName?: string | null
  authorVids?: string | null
  /** 首次采集时间（UTC ISO 字符串）。「今日新增」口径用它，不用 lastCollectedAt */
  firstCollectedAt?: string | null
  /** 最近一次采集时间。每次 upsert 都会刷新，所以**不能**用来数「今天新增」 */
  lastCollectedAt?: string | null
  /** 采集来源列表，形如 ["category:300000", "ranking:rising"] */
  collectSource?: string[] | null
}

/** 图书列表查询条件，对应后端 BookQuery 的 12 个参数 */
export interface BookQuery {
  categoryId?: number
  minRating?: number
  maxRating?: number
  minReadingCount?: number
  targetType?: TargetType | ''
  targetId?: string
  author?: string
  minPublishTime?: string
  maxPublishTime?: string
  sortField?: BookSortField
  asc?: boolean
  page?: number
  size?: number
}

/** 排序字段白名单。后端 `resolveSortPath()` 里不认识的值会 fallback 到 newRating */
export type BookSortField =
  | 'newRating'
  | 'newRatingCount'
  | 'readingCount'
  | 'publishTime'
  | 'lastCollectedAt'

export const BOOK_SORT_OPTIONS: Array<{ label: string; value: BookSortField }> = [
  { label: '推荐值', value: 'newRating' },
  { label: '评价人数', value: 'newRatingCount' },
  { label: '阅读人数', value: 'readingCount' },
  { label: '出版时间', value: 'publishTime' },
  { label: '采集时间', value: 'lastCollectedAt' },
]

/** 图书可编辑字段白名单（13 个）。刻意不含 bookId / searchIdx / collectSource / firstCollectedAt */
export interface BookUpdateRequest {
  title?: string
  author?: string
  translator?: string
  intro?: string
  category?: string
  categories?: BookCategory[]
  publishTime?: string
  language?: string
  ispub?: number
  finished?: number
  free?: number
  price?: number
  originalPrice?: number
}

/** 批量删除结果 */
export interface BatchDeleteResult {
  requested: number
  deleted: number
}

// ======================================================================
// 字典（com.bookcollector.taxonomy.entity.TaxonomyItem）
// ======================================================================

export interface TaxonomyItem {
  id?: string | null
  type: TargetType
  /** 采集目标的身份。**编辑时不允许修改** */
  code: string
  name: string
  enabled?: boolean | null
  sort?: number | null
  remark?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface TaxonomyRequest {
  code?: string
  name?: string
  enabled?: boolean
  sort?: number
  remark?: string
}

// ======================================================================
// 采集任务（com.bookcollector.task.entity.*）
// ======================================================================

export interface CollectTask {
  id?: string | null
  name: string
  targetType: TargetType
  targetId: string
  targetName?: string | null
  maxPages?: number | null
  status: TaskStatus
  remark?: string | null
  lastRunId?: string | null
  runCount?: number | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** 新建任务请求 */
export interface TaskCreateRequest {
  name: string
  targetType: TargetType
  targetId: string
  targetName?: string
  maxPages?: number
  remark?: string
}

/**
 * 编辑任务请求。
 *
 * ⚠️ 只有 3 个字段 —— `targetType` / `targetId` **刻意不在里面**。
 * 它们是游标的归属键，改了会让游标与目标脱钩，所以让「不能改」在类型层面成立。
 */
export interface TaskUpdateRequest {
  name?: string
  maxPages?: number
  remark?: string
}

export interface TaskRun {
  id?: string | null
  taskId: string
  taskName?: string | null
  targetType?: TargetType | null
  targetId?: string | null
  targetName?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  status: TaskStatus
  pagesDone?: number | null
  booksSaved?: number | null
  /** 这次运行跑到哪了（进度快照，给界面看）。**续传的权威依据是游标，不是这个** */
  currentCursor?: number | null
  totalCount?: number | null
  lastProgressAt?: string | null
  errorMsg?: string | null
}

export interface TaskLog {
  id?: string | null
  runId?: string | null
  taskId?: string | null
  level?: string | null
  message?: string | null
  createdAt?: string | null
}

/** 任务删除结果（级联删了 runs + logs，**不删游标**） */
export interface TaskDeleteResult {
  taskId: string
  runsDeleted: number
  logsDeleted: number
}

export interface RunQuery {
  taskId?: string
  status?: TaskStatus | ''
  page?: number
  size?: number
}

// ======================================================================
// 采集游标（com.bookcollector.cursor.entity.CollectCursor）
// ======================================================================

export interface CollectCursor {
  id?: string | null
  targetType: TargetType
  targetId: string
  targetName?: string | null
  /** 「下次从哪开始」。这是续传的**唯一权威**依据 */
  maxIndex?: number | null
  totalCollected?: number | null
  totalCount?: number | null
  finished?: boolean | null
  lastRunId?: string | null
  updatedAt?: string | null
}

/** 设置游标请求。`maxIndex = 0` 即「重置」 */
export interface CursorSetRequest {
  targetType: TargetType
  targetId: string
  targetName?: string
  maxIndex?: number
}

export interface CursorSetResult {
  targetType: string
  targetId: string
  maxIndex: number
  note: string
}

// ======================================================================
// 请求审计（com.bookcollector.audit.entity.ApiRequest）
// ======================================================================

export interface ApiRequest {
  id?: string | null
  runId?: string | null
  targetType?: string | null
  targetId?: string | null
  pageIndex?: number | null
  maxIndex?: number | null
  url?: string | null
  statusCode?: number | null
  responseTimeMs?: number | null
  synckey?: number | null
  totalCount?: number | null
  hasMore?: boolean | null
  resultCount?: number | null
  errorMsg?: string | null
  createdAt?: string | null
}

export interface ApiRequestQuery {
  runId?: string
  targetType?: TargetType | ''
  targetId?: string
  onlyFailed?: boolean
  page?: number
  size?: number
}

// ======================================================================
// 仪表盘统计（com.bookcollector.stats.StatsService）
// ======================================================================

export interface StatsOverview {
  /** 图书总数 */
  bookTotal: number
  /** 分类总数 */
  categoryTotal: number
  /** 任务总数 */
  taskTotal: number
  /** 今日新增（按 firstCollectedAt 数「净新增」） */
  todayCollected: number

  // --- 上下文 ---
  categoryEnabled: number
  rankingTotal: number
  rankingEnabled: number
  /** 运行中的任务数（RUNNING + PAUSED） */
  taskRunning: number
  /** 内存里活跃的采集线程数 */
  activeRuns: number
  cursorTotal: number
  requestTotal: number
  last7DaysCollected: number
  lastCollectedAt?: string | null
}

/** 分类分布（饼图）。按「采集目标」分，不按平台分类分 */
export interface CategoryDistributionItem {
  sourceKey: string
  type: string
  targetId: string
  /** 字典里查不到时是 sourceKey 本身（不隐藏异常数据） */
  name: string
  count: number
}

/** 评分分布（柱图）。固定 10 个桶，空桶已补齐 */
export interface RatingDistributionItem {
  label: string
  min: number
  max: number
  count: number
}

/** 出版年份趋势（折线图）。年份已过滤到 1900–2100 */
export interface PublishYearTrendItem {
  year: string
  count: number
}

/** 近 7 天采集量（柱图）。固定 7 天，空日期已补齐 */
export interface RecentCollectedItem {
  date: string
  count: number
}

export interface StatsCharts {
  categoryDistribution: CategoryDistributionItem[]
  ratingDistribution: RatingDistributionItem[]
  publishYearTrend: PublishYearTrendItem[]
  recentCollected: RecentCollectedItem[]
}
