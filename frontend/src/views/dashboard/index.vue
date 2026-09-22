<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getCharts, getOverview } from '@/api/stats'
import { ping, type PingData } from '@/api/ping'
import { formatDateTime, formatNumber, formatRelative } from '@/utils/format'
import {
  AXIS_LABEL_STYLE,
  CHART_COLORS,
  SPLIT_LINE_STYLE,
  type ChartOption,
} from '@/utils/echarts'
import type { StatsCharts, StatsOverview } from '@/types'
import BaseChart from '@/components/BaseChart.vue'

/**
 * 仪表盘。
 *
 * <h3>两个接口、两个加载状态，互不阻塞</h3>
 * `/stats/overview` 是 4 个 `count`（常数级），`/stats/charts` 是 4 条聚合管道
 * （分类分布要 `$unwind` 5 万份文档的数组字段）。合成一个请求会让
 * **4 个指标卡陪着图表一起转圈**。所以这里 `Promise.all` 只是等「全部结束」好更新时间戳，
 * 各自的 loading 是独立的。
 *
 * <h3>图表配置写在 computed 里，不是 onMounted 里</h3>
 * ECharts 是命令式的，很容易写成「挂载时 setOption 一次」。但那样手动刷新后
 * 数据变了图不会变。这里把 option 做成 computed，`BaseChart` 内部 watch 它并
 * 用 `setOption(option, true)` 整份替换 —— 数据驱动，刷新即生效。
 *
 * <h3>「今日新增」为什么是 39 而「近 7 天」是 140</h3>
 * 两个口径都按 `firstCollectedAt`（净新增），不是 `lastCollectedAt`。
 * 后者每次 upsert 都刷新，重采同一批书会让它「今天又采了一万本」。
 */

const overview = ref<StatsOverview | null>(null)
const overviewLoading = ref(false)
const overviewError = ref('')

const charts = ref<StatsCharts | null>(null)
const chartsLoading = ref(false)
const chartsError = ref('')

const pingData = ref<PingData | null>(null)
const pingLoading = ref(false)
const pingError = ref('')

const lastRefreshAt = ref('')

async function loadOverview(): Promise<void> {
  overviewLoading.value = true
  overviewError.value = ''
  try {
    overview.value = await getOverview()
  } catch (e) {
    overviewError.value = e instanceof Error ? e.message : String(e)
    overview.value = null
  } finally {
    overviewLoading.value = false
  }
}

async function loadCharts(): Promise<void> {
  chartsLoading.value = true
  chartsError.value = ''
  try {
    charts.value = await getCharts()
  } catch (e) {
    chartsError.value = e instanceof Error ? e.message : String(e)
    charts.value = null
  } finally {
    chartsLoading.value = false
  }
}

async function loadPing(): Promise<void> {
  pingLoading.value = true
  pingError.value = ''
  try {
    pingData.value = await ping()
  } catch (e) {
    pingError.value = e instanceof Error ? e.message : String(e)
    pingData.value = null
  } finally {
    pingLoading.value = false
  }
}

/** 三个请求并发发出去 —— 耗时差一个数量级，串行会让总耗时等于三者之和 */
async function refreshAll(): Promise<void> {
  await Promise.all([loadOverview(), loadCharts(), loadPing()])
  lastRefreshAt.value = formatDateTime(Date.now())
}

onMounted(refreshAll)

// ======================================================================
// 指标卡
// ======================================================================

interface MetricCard {
  key: string
  label: string
  value: number
  icon: string
  color: string
  hint: string
}

const metrics = computed<MetricCard[]>(() => {
  const o = overview.value
  if (!o) {
    return []
  }
  return [
    {
      key: 'bookTotal',
      label: '图书总数',
      value: o.bookTotal,
      icon: 'Reading',
      color: '#409eff',
      hint: `近 7 天新增 ${formatNumber(o.last7DaysCollected)}`,
    },
    {
      key: 'categoryTotal',
      label: '分类总数',
      value: o.categoryTotal,
      icon: 'Collection',
      color: '#67c23a',
      hint: `启用 ${formatNumber(o.categoryEnabled)} · 榜单 ${formatNumber(o.rankingTotal)}`,
    },
    {
      key: 'taskTotal',
      label: '采集任务',
      value: o.taskTotal,
      icon: 'List',
      color: '#e6a23c',
      hint: `运行中 ${formatNumber(o.taskRunning)} · 活跃线程 ${formatNumber(o.activeRuns)}`,
    },
    {
      key: 'todayCollected',
      label: '今日新增',
      value: o.todayCollected,
      icon: 'TrendCharts',
      color: '#f56c6c',
      hint: '口径：firstCollectedAt（净新增）',
    },
  ]
})

// ======================================================================
// 图 1 · 分类分布（环形饼图）
// ======================================================================

/**
 * 用环形（donut）而不是实心饼：中心空出来可以放总数，
 * 而且 30 个扇区的实心饼会糊成一团，环形至少能看清比例。
 *
 * 标签默认隐藏（30 个标签会互相压），靠 hover 时 `emphasis` 显示 +
 * 右侧可滚动图例来识别。
 */
const categoryPieOption = computed<ChartOption>(() => {
  const rows = charts.value?.categoryDistribution ?? []
  if (!rows.length) {
    return { series: [] }
  }
  return {
    color: CHART_COLORS,
    tooltip: {
      trigger: 'item',
      formatter: '{b}<br/>{c} 本（{d}%）',
    },
    legend: {
      type: 'scroll',
      orient: 'vertical',
      right: 0,
      top: 'middle',
      itemWidth: 10,
      itemHeight: 10,
      textStyle: AXIS_LABEL_STYLE,
    },
    series: [
      {
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['36%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 1 },
        label: { show: false },
        labelLine: { show: false },
        emphasis: {
          label: { show: true, fontSize: 13, fontWeight: 'bold', color: '#303133', formatter: '{b}\n{c} 本' },
        },
        data: rows.map((row) => ({ name: row.name, value: row.count })),
      },
    ],
  }
})

/** 饼图卡片的摘要行 */
const categorySummary = computed(() => {
  const rows = charts.value?.categoryDistribution ?? []
  if (!rows.length) {
    return '暂无数据'
  }
  const total = rows.reduce((sum, row) => sum + row.count, 0)
  const top = rows[0]
  // ⚠️ 一个副作用：同一本书若既在分类又在榜单里会被计两次，所以扇区之和 > 图书总数
  return `共 ${rows.length} 个采集目标贡献 ${total} 本次入库（同一本书可能既属分类又属榜单，会重复计数）· 最多的是「${top.name}」${top.count} 本`
})

// ======================================================================
// 图 2 · 评分分布（柱图）
// ======================================================================

/**
 * 10 个桶（0-100、100-200 … 900-1000）。后端已补齐空桶，
 * 所以 X 轴永远是 10 格 —— 不会出现「缺格」让人误以为分布是连续的。
 *
 * ⚠️ 数据源是**顶层** `newRating`，不是 `newRatingDetail.newRating`（后者不存在）。
 */
const ratingBarOption = computed<ChartOption>(() => {
  const rows = charts.value?.ratingDistribution ?? []
  if (!rows.length) {
    return { series: [] }
  }
  return {
    grid: { left: 8, right: 20, top: 28, bottom: 4, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: '{b} 分<br/>{c} 本' },
    xAxis: {
      type: 'category',
      data: rows.map((row) => row.label),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#dcdfe6' } },
      axisLabel: { ...AXIS_LABEL_STYLE, rotate: 32, fontSize: 11 },
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: AXIS_LABEL_STYLE,
      splitLine: SPLIT_LINE_STYLE,
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 30,
        itemStyle: { color: '#409eff', borderRadius: [3, 3, 0, 0] },
        label: { show: true, position: 'top', fontSize: 11, color: '#909399' },
        data: rows.map((row) => row.count),
      },
    ],
  }
})

const ratingSummary = computed(() => {
  const rows = charts.value?.ratingDistribution ?? []
  if (!rows.length) {
    return '暂无数据'
  }
  const total = rows.reduce((sum, row) => sum + row.count, 0)
  const top = rows.reduce((a, b) => (b.count > a.count ? b : a), rows[0])
  return `有推荐值的图书 ${total} 本 · 最集中的区间是 ${top.label}（${top.count} 本）`
})

// ======================================================================
// 图 3 · 出版年份趋势（折线图）
// ======================================================================

/**
 * 年份已由后端过滤到 1900–2100（库里有 `"0000-00-00 00:00:00"` 这类占位值，
 * 不过滤会出现一个「0000 年」的尖峰）。
 *
 * 用平滑曲线 + 淡面积：趋势图的重点是「走向」而不是「每个点的精确值」，
 * 折线拐角太硬会让人误以为有突变。
 */
const yearLineOption = computed<ChartOption>(() => {
  const rows = charts.value?.publishYearTrend ?? []
  if (!rows.length) {
    return { series: [] }
  }
  return {
    grid: { left: 8, right: 20, top: 28, bottom: 4, containLabel: true },
    tooltip: { trigger: 'axis', formatter: '{b} 年<br/>{c} 本' },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: rows.map((row) => row.year),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#dcdfe6' } },
      axisLabel: { ...AXIS_LABEL_STYLE, rotate: 32, fontSize: 11 },
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: AXIS_LABEL_STYLE,
      splitLine: SPLIT_LINE_STYLE,
    },
    series: [
      {
        type: 'line',
        smooth: true,
        symbolSize: 6,
        itemStyle: { color: '#67c23a' },
        lineStyle: { width: 2 },
        areaStyle: { opacity: 0.12 },
        data: rows.map((row) => row.count),
      },
    ],
  }
})

const yearSummary = computed(() => {
  const rows = charts.value?.publishYearTrend ?? []
  if (!rows.length) {
    return '暂无数据'
  }
  const total = rows.reduce((sum, row) => sum + row.count, 0)
  const top = rows.reduce((a, b) => (b.count > a.count ? b : a), rows[0])
  return `覆盖 ${rows.length} 个年份、${total} 本 · 最多的是 ${top.year} 年（${top.count} 本）`
})

// ======================================================================
// 图 4 · 近 7 天采集量（柱图）
// ======================================================================

/**
 * 后端已补齐没有数据的日期（固定 7 天）。
 * 不补的话管道只会返回「有书的日子」，一张 7 天柱图可能只有 3 根柱子 ——
 * 横轴不是时间轴而是「有数据的日子」，视觉上会让人以为那几天之间没有间隔。
 */
const recentBarOption = computed<ChartOption>(() => {
  const rows = charts.value?.recentCollected ?? []
  if (!rows.length) {
    return { series: [] }
  }
  return {
    grid: { left: 8, right: 20, top: 28, bottom: 4, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: '{b}<br/>{c} 本' },
    xAxis: {
      type: 'category',
      data: rows.map((row) => row.date.slice(5)),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#dcdfe6' } },
      axisLabel: AXIS_LABEL_STYLE,
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: AXIS_LABEL_STYLE,
      splitLine: SPLIT_LINE_STYLE,
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 34,
        itemStyle: { color: '#e6a23c', borderRadius: [3, 3, 0, 0] },
        label: { show: true, position: 'top', fontSize: 11, color: '#909399' },
        data: rows.map((row) => row.count),
      },
    ],
  }
})

const recentSummary = computed(() => {
  const rows = charts.value?.recentCollected ?? []
  if (!rows.length) {
    return '暂无数据'
  }
  const total = rows.reduce((sum, row) => sum + row.count, 0)
  return `近 7 天净新增 ${total} 本（固定 7 天，无数据的日子已补 0）`
})
</script>

<template>
  <div class="page">
    <!-- ================= 头部 ================= -->
    <div class="page-head">
      <div>
        <h2 class="page-title">仪表盘</h2>
        <p class="page-sub">
          <span v-if="lastRefreshAt">最后刷新：{{ lastRefreshAt }}</span>
          <span v-else>加载中…</span>
          <span class="text-muted">　手动刷新，不做实时推送</span>
        </p>
      </div>
      <el-button type="primary" :loading="overviewLoading || chartsLoading" @click="refreshAll">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
    </div>

    <!-- ================= 4 个指标卡 ================= -->
    <el-alert
      v-if="overviewError"
      type="error"
      :closable="false"
      show-icon
      title="指标卡加载失败"
      :description="overviewError"
    />

    <div v-else class="metrics">
      <template v-if="overviewLoading && !overview">
        <el-card v-for="i in 4" :key="i" shadow="never" class="metric">
          <el-skeleton :rows="2" animated />
        </el-card>
      </template>

      <el-card v-for="card in metrics" :key="card.key" shadow="never" class="metric">
        <div class="metric-body">
          <div class="metric-icon" :style="{ background: card.color + '1a', color: card.color }">
            <el-icon :size="22"><component :is="card.icon" /></el-icon>
          </div>
          <div class="metric-text">
            <div class="metric-label">{{ card.label }}</div>
            <div class="metric-value num">{{ formatNumber(card.value) }}</div>
            <div class="metric-hint">{{ card.hint }}</div>
          </div>
        </div>
      </el-card>
    </div>

    <!-- ================= 4 张图 ================= -->
    <el-alert
      v-if="chartsError"
      type="error"
      :closable="false"
      show-icon
      title="图表数据加载失败"
      :description="chartsError"
    />

    <div v-else class="charts">
      <el-card shadow="never" class="chart-card">
        <template #header>
          <div class="card-head">
            <span class="card-title">分类分布</span>
            <el-tooltip
              content="按「采集目标」分，不按平台的分类体系分。同一本书既在分类又在榜单里会被计两次。"
              placement="top"
            >
              <el-icon class="help"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
        </template>
        <p class="chart-summary">{{ categorySummary }}</p>
        <BaseChart :option="categoryPieOption" :loading="chartsLoading" height="280px" />
      </el-card>

      <el-card shadow="never" class="chart-card">
        <template #header>
          <div class="card-head">
            <span class="card-title">评分分布</span>
            <el-tooltip content="推荐值 0~1000，每 100 分一桶。空桶已补齐。" placement="top">
              <el-icon class="help"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
        </template>
        <p class="chart-summary">{{ ratingSummary }}</p>
        <BaseChart :option="ratingBarOption" :loading="chartsLoading" height="280px" />
      </el-card>

      <el-card shadow="never" class="chart-card">
        <template #header>
          <div class="card-head">
            <span class="card-title">出版年份趋势</span>
            <el-tooltip content="已过滤到 1900–2100，排除「0000-00-00」这类占位值。" placement="top">
              <el-icon class="help"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
        </template>
        <p class="chart-summary">{{ yearSummary }}</p>
        <BaseChart :option="yearLineOption" :loading="chartsLoading" height="280px" />
      </el-card>

      <el-card shadow="never" class="chart-card">
        <template #header>
          <div class="card-head">
            <span class="card-title">近 7 天采集量</span>
            <el-tooltip
              content="按 firstCollectedAt 数「净新增」，与「今日新增」指标卡同一口径。"
              placement="top"
            >
              <el-icon class="help"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
        </template>
        <p class="chart-summary">{{ recentSummary }}</p>
        <BaseChart :option="recentBarOption" :loading="chartsLoading" height="280px" />
      </el-card>
    </div>

    <!-- ================= 其他指标 ================= -->
    <el-card v-if="overview" shadow="never">
      <template #header>
        <span class="card-title">其他指标</span>
      </template>
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="启用分类">{{ overview.categoryEnabled }}</el-descriptions-item>
        <el-descriptions-item label="榜单总数">{{ overview.rankingTotal }}</el-descriptions-item>
        <el-descriptions-item label="启用榜单">{{ overview.rankingEnabled }}</el-descriptions-item>
        <el-descriptions-item label="运行中任务">{{ overview.taskRunning }}</el-descriptions-item>
        <el-descriptions-item label="活跃采集线程">{{ overview.activeRuns }}</el-descriptions-item>
        <el-descriptions-item label="游标数">{{ overview.cursorTotal }}</el-descriptions-item>
        <el-descriptions-item label="审计请求数">{{ overview.requestTotal }}</el-descriptions-item>
        <el-descriptions-item label="近 7 天新增">{{ overview.last7DaysCollected }}</el-descriptions-item>
        <el-descriptions-item label="最近采集时间" :span="2">
          {{ formatDateTime(overview.lastCollectedAt) }}
          <span class="text-muted">（{{ formatRelative(overview.lastCollectedAt) }}）</span>
        </el-descriptions-item>
        <el-descriptions-item label="数据库">
          <span class="text-mono">bookcollector @ localhost:27017</span>
        </el-descriptions-item>
        <el-descriptions-item label="服务端时间">
          <span class="text-mono">{{ pingData?.time ?? '-' }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- ================= 运行时环境 ================= -->
    <el-alert
      v-if="pingError"
      type="warning"
      :closable="false"
      show-icon
      title="服务连通性检查失败"
      :description="pingError"
    />

    <el-card v-if="pingLoading || pingData" shadow="never">
      <template #header>
        <span class="card-title">运行时环境</span>
      </template>
      <el-descriptions v-if="pingData" :column="3" border size="small">
        <el-descriptions-item label="应用名">{{ pingData.app }}</el-descriptions-item>
        <el-descriptions-item label="Java 版本">{{ pingData.javaVersion }}</el-descriptions-item>
        <el-descriptions-item label="file.encoding">{{ pingData.fileEncoding }}</el-descriptions-item>
        <el-descriptions-item label="JVM">{{ pingData.jvm }}</el-descriptions-item>
        <el-descriptions-item label="traceId">
          <span class="text-mono">{{ pingData.mdThreadId }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="中文编码">
          <span :class="{ bad: !pingData.chineseTest.includes('微信读书') }">
            {{ pingData.chineseTest }}
          </span>
        </el-descriptions-item>
      </el-descriptions>
      <el-skeleton v-else :rows="2" animated />
    </el-card>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.page-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}
.page-sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: #909399;
}

.card-title {
  font-size: 14px;
  font-weight: 500;
}
.card-head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.help {
  color: #c0c4cc;
  cursor: help;
  font-size: 14px;
}

/* ---------------- 指标卡 ---------------- */

.metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}
@media (max-width: 1100px) {
  .metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.metric :deep(.el-card__body) {
  padding: 16px;
}
.metric-body {
  display: flex;
  align-items: center;
  gap: 14px;
}
.metric-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 8px;
  flex-shrink: 0;
}
.metric-text {
  min-width: 0;
}
.metric-label {
  font-size: 13px;
  color: #909399;
}
.metric-value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.3;
  color: #303133;
}
.metric-hint {
  font-size: 12px;
  color: #a8abb2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------------- 图表卡 ---------------- */

.charts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
@media (max-width: 1100px) {
  .charts {
    grid-template-columns: minmax(0, 1fr);
  }
}

.chart-card :deep(.el-card__header) {
  padding: 10px 16px;
}
.chart-card :deep(.el-card__body) {
  padding: 14px 16px 16px;
}
.chart-summary {
  margin: 0 0 6px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.bad {
  color: #f56c6c;
  font-weight: 500;
}
</style>
