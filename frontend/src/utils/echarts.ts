/**
 * ECharts 按需注册。
 *
 * <h3>为什么不用 `import * as echarts from 'echarts'`</h3>
 * 全量引入会把**所有**图表类型（地图、关系图、桑基图、3D…）打进包里，约 1 MB。
 * 本项目只需要饼 / 柱 / 折线三种，按需注册后 echarts 部分约 300 KB。
 *
 * ⚠️ **漏注册的后果是运行时白屏 + 控制台一行警告**（形如
 * `Component series.pie not exists. Load it first.`），而不是编译错误。
 * 所以新增图表类型时，**必须回来这里 `use()` 一下**。
 *
 * <h3>为什么这里统一导出类型</h3>
 * `echarts/core` 的 `ComposeOption` 需要把用到的 series/component 选项类型拼起来，
 * 拼错了 option 的类型提示就没了。集中在这里拼一次，页面只 import `ChartOption`。
 */

import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { ComposeOption } from 'echarts/core'
import type {
  BarSeriesOption,
  LineSeriesOption,
  PieSeriesOption,
} from 'echarts/charts'
import type {
  GridComponentOption,
  LegendComponentOption,
  TooltipComponentOption,
} from 'echarts/components'

echarts.use([
  // 图表类型
  BarChart,
  LineChart,
  PieChart,
  // 组件
  GridComponent, // 直角坐标系的坐标系（柱图/折线图必需）
  TooltipComponent,
  LegendComponent,
  // 渲染器
  CanvasRenderer,
])

/** 本项目用得到的图表配置联合类型 */
export type ChartOption = ComposeOption<
  | BarSeriesOption
  | LineSeriesOption
  | PieSeriesOption
  | GridComponentOption
  | LegendComponentOption
  | TooltipComponentOption
>

/** 统一的配色。与 Element Plus 的默认色板对齐，免得图表和按钮像两个系统 */
export const CHART_COLORS = [
  '#409eff',
  '#67c23a',
  '#e6a23c',
  '#f56c6c',
  '#909399',
  '#7b68ee',
  '#00bcd4',
  '#ff9800',
  '#9c27b0',
  '#4caf50',
]

/** 坐标轴/图例的文字样式。写死颜色是因为这套后台是浅色的，不跟随系统深色模式 */
export const AXIS_LABEL_STYLE = {
  color: '#606266',
  fontSize: 12,
} as const

/** 网格线颜色 */
export const SPLIT_LINE_STYLE = {
  lineStyle: {
    color: '#ebeef5',
  },
} as const

export { echarts }
