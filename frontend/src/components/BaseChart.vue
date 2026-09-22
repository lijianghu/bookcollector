<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { echarts, type ChartOption } from '@/utils/echarts'

/**
 * ECharts 封装。
 *
 * <h3>为什么值得包一层</h3>
 * ECharts 是「命令式 + 手动生命周期」的库，直接用会有三个必踩的坑，
 * 每个坑都只在特定场景下出现（所以很容易漏）：
 *
 * 1. **必须 `dispose()`** —— ECharts 实例会挂 window 级监听（resize、设备像素比变化）。
 *    不 dispose，切页面切十次就泄漏十个实例，还会出现「旧图还在响应 resize」的怪象。
 * 2. **容器尺寸变化必须 `resize()`** —— 侧边栏折叠时容器宽度变了但**窗口尺寸没变**，
 *    所以监听 `window.resize` 是**监听不到**的。必须用 `ResizeObserver`。
 * 3. **`setOption` 默认是合并（merge）语义** —— 数据从 18 个年份变成 5 个时，
 *    旧的第 6~18 个数据点会**留在图上**。所以第二参数必须传 `true`（notMerge）。
 *
 * 这三个坑都在这里一次性处理掉，页面只管传 `option`。
 */

const props = withDefaults(
  defineProps<{
    /** 图表配置。变化时会整份替换（notMerge） */
    option: ChartOption
    /** 高度。宽度自适应容器 */
    height?: string
    /** 显示 ECharts 自带的 loading 遮罩 */
    loading?: boolean
    /** 空数据时显示的文案。传空字符串则不显示 */
    emptyText?: string
  }>(),
  {
    height: '300px',
    loading: false,
    emptyText: '暂无数据',
  },
)

const container = ref<HTMLDivElement>()
/** 用 shallowRef：ECharts 实例是个大对象，深度响应式化纯属浪费，还会拖慢 setOption */
const chart = shallowRef<echarts.ECharts>()
let resizeObserver: ResizeObserver | undefined

function ensureChart(): echarts.ECharts | undefined {
  if (!container.value) {
    return undefined
  }
  if (!chart.value) {
    chart.value = echarts.init(container.value)
  }
  return chart.value
}

function render(): void {
  const instance = ensureChart()
  if (!instance) {
    return
  }
  // ★ notMerge = true：整份替换，否则旧数据的多余数据点会残留
  instance.setOption(props.option, true)
  instance.resize()
}

function syncLoading(): void {
  const instance = chart.value
  if (!instance) {
    return
  }
  if (props.loading) {
    instance.showLoading({
      text: '加载中',
      color: '#409eff',
      textColor: '#606266',
      maskColor: 'rgba(255, 255, 255, 0.85)',
    })
  } else {
    instance.hideLoading()
  }
}

/**
 * 有没有 series。
 *
 * ⚠️ `option.series` 的类型是「单个对象 **或** 数组」（ECharts 允许 `series: {...}`），
 * 所以不能直接 `.length` —— 得先判类型。空数据时页面传的是 `series: []`。
 */
const hasSeries = computed(() => {
  const series = props.option.series
  if (series === undefined || series === null) {
    return false
  }
  return Array.isArray(series) ? series.length > 0 : true
})

onMounted(() => {
  render()
  syncLoading()

  // ★ 用 ResizeObserver 而不是 window.resize —— 侧边栏折叠时窗口尺寸没变
  if (container.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => {
      chart.value?.resize()
    })
    resizeObserver.observe(container.value)
  }
})

watch(() => props.option, render, { deep: true })
watch(() => props.loading, syncLoading)

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = undefined
  // ★ 必须 dispose：ECharts 会挂 window 级监听，不 dispose 就泄漏
  chart.value?.dispose()
  chart.value = undefined
})

defineExpose({ resize: () => chart.value?.resize() })
</script>

<template>
  <div class="base-chart" :style="{ height }">
    <div ref="container" class="canvas" />
    <!--
      空数据时不渲染 canvas，改成一行提示。
      为什么不用 ECharts 的 graphic 空状态：那样 canvas 还是占着位置，
      而且「0 条数据」的图看起来像加载失败，不如直接说清楚。
    -->
    <div v-if="emptyText && !loading && !hasSeries" class="empty">
      {{ emptyText }}
    </div>
  </div>
</template>

<style scoped>
.base-chart {
  position: relative;
  width: 100%;
}
.canvas {
  width: 100%;
  height: 100%;
}
.empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: #a8abb2;
}
</style>
