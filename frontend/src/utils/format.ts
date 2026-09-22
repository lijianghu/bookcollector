/**
 * 展示层格式化工具。
 *
 * ⚠️ 后端给的时间是 **UTC 的 ISO-8601 字符串**（形如 `"2026-09-21T11:39:48.584+00:00"`）。
 * `new Date(iso)` 会正确解析成 UTC 瞬间，再按浏览器本地时区渲染 —— 这是对的。
 *
 * 所以本文件里**所有**时间格式化都必须经过 `new Date()`，
 * 不要写 `iso.slice(0, 19).replace('T', ' ')` 这种「看起来更简单」的版本：
 * 那会把 UTC 当本地时间显示，中国时区下整体差 8 小时，
 * 而且「今天的采集量」这种跨天判断会算错。
 */

/** 补零 */
function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

/** ISO 字符串 → `YYYY-MM-DD HH:mm:ss`（本地时区）。无效值返回 `-` */
export function formatDateTime(value?: string | number | null): string {
  if (value === null || value === undefined || value === '') {
    return '-'
  }
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) {
    return '-'
  }
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

/** ISO 字符串 → `YYYY-MM-DD`（本地时区） */
export function formatDate(value?: string | number | null): string {
  if (value === null || value === undefined || value === '') {
    return '-'
  }
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) {
    return '-'
  }
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/**
 * 相对时间，形如「3 分钟前」。
 *
 * 用途是任务进度 —— 用户盯着「最后更新」看任务有没有卡死，
 * 「2 分钟前」比「2026-09-22 15:31:04」直观得多。
 */
export function formatRelative(value?: string | number | null): string {
  if (value === null || value === undefined || value === '') {
    return '-'
  }
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) {
    return '-'
  }
  const diff = Date.now() - d.getTime()
  if (diff < 0) {
    return '刚刚'
  }
  const sec = Math.floor(diff / 1000)
  if (sec < 60) {
    return `${sec} 秒前`
  }
  const min = Math.floor(sec / 60)
  if (min < 60) {
    return `${min} 分钟前`
  }
  const hour = Math.floor(min / 60)
  if (hour < 24) {
    return `${hour} 小时前`
  }
  const day = Math.floor(hour / 24)
  if (day < 30) {
    return `${day} 天前`
  }
  return formatDate(value)
}

/** 千分位。`null` / 非数字 → `-` */
export function formatNumber(value?: number | null): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '-'
  }
  return value.toLocaleString('zh-CN')
}

/**
 * 空值统一显示为 `-`。
 *
 * 为什么不用 `value || '-'`：那样 `0` 和 `false` 也会变成 `-`，
 * 而「评分 0」和「评分未知」是两件完全不同的事。
 */
export function dash(value?: string | number | boolean | null): string {
  if (value === null || value === undefined || value === '') {
    return '-'
  }
  if (typeof value === 'boolean') {
    return value ? '是' : '否'
  }
  return String(value)
}

/**
 * 后端 `Integer` 当布尔用的字段 → 人话。
 *
 * 微信读书接口里大量 `0/1` 表示真假（`finished` / `free` / `ispub` ...），
 * 直接显示 0/1 没人看得懂。
 */
export function flagText(value?: number | null, trueText = '是', falseText = '否'): string {
  if (value === null || value === undefined) {
    return '-'
  }
  return value === 1 ? trueText : falseText
}

/**
 * 推荐值 0~1000 → 展示用的星级（0~5）。
 *
 * ⚠️ 是**顶层** `newRating`，不是 `newRatingDetail.newRating`（后者不存在）。
 */
export function ratingToStars(rating?: number | null): string {
  if (rating === null || rating === undefined) {
    return '-'
  }
  const stars = Math.round(rating / 200 * 2) / 2 // 保留 0.5 星
  return `${stars.toFixed(1)} / 5`
}

/**
 * 出版时间的**列表展示**格式。
 *
 * <h3>为什么不用 formatDate / formatDateTime</h3>
 * `publishTime` 虽然长得像时间，但它**不是时间戳，是字符串**：
 * 库里既有 `"2015-05-01 00:00:00"`，也有只有年份的脏值 `"2022"`。
 * 而且后端 `BookQuery.resolveMaxPublishTime()` 是靠**字典序**比较它来做区间筛选的。
 *
 * 所以这里只做「按已有精度截短」，绝不 `new Date()` 解析 ——
 * 解析会把 `"2022"` 补成 1 月 1 日，凭空造出一个库里没有的信息，
 * 而且和「填 2022 表示 2022 全年」这个筛选语义自相矛盾。
 *
 * 列表里秒级精度没有意义（19 个字符在 100px 的列里必然换行，行高就不齐了），
 * 所以截到「天」；完整原值在详情抽屉里看。
 *
 * <table border="1">
 *   <tr><th>输入</th><th>输出</th></tr>
 *   <tr><td>{@code "2015-05-01 00:00:00"}</td><td>{@code "2015-05-01"}</td></tr>
 *   <tr><td>{@code "2022-06"}</td><td>{@code "2022-06"}</td></tr>
 *   <tr><td>{@code "2022"}（脏值）</td><td>{@code "2022"}</td></tr>
 *   <tr><td>{@code ""} / {@code null}</td><td>{@code "-"}</td></tr>
 * </table>
 */
export function formatPublishDate(value?: string | null): string {
  if (value === null || value === undefined || value === '') {
    return '-'
  }
  const matched = /^(\d{4})(?:-(\d{1,2}))?(?:-(\d{1,2}))?/.exec(value)
  if (!matched) {
    // 认不出来的格式原样返回，不做猜测
    return value
  }
  const [, year, month, day] = matched
  if (day) {
    return `${year}-${month}-${day}`
  }
  if (month) {
    return `${year}-${month}`
  }
  return year
}

/** 从 "category:300000" 拆出 { type, targetId } */
export function parseSourceKey(sourceKey?: string | null): { type: string; targetId: string } {
  if (!sourceKey) {
    return { type: '', targetId: '' }
  }
  const idx = sourceKey.indexOf(':')
  if (idx <= 0) {
    return { type: '', targetId: sourceKey }
  }
  return {
    type: sourceKey.slice(0, idx).toUpperCase(),
    targetId: sourceKey.slice(idx + 1),
  }
}
