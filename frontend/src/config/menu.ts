/**
 * 菜单配置。
 *
 * 侧边栏和面包屑都从这里读，**唯一真相来源** —— 不要在 `BasicLayout` 里
 * 另写一份菜单数组，否则加一个页面要改两个地方，迟早不一致。
 *
 * `icon` 是 `@element-plus/icons-vue` 的**组件名**（main.ts 里全局注册了）。
 * 名字写错不会报错，只会渲染出一个空位 —— 这是 Element Plus 图标全局注册的代价，
 * 所以改这里之后要肉眼确认一眼侧边栏。
 */

export interface MenuItem {
  /** 路由路径，必须与 router 里的 path 一致 */
  path: string
  /** 菜单与面包屑显示的名字 */
  title: string
  /** Element Plus 图标组件名 */
  icon: string
  /** 一句话说明这个页面干什么。页面外壳会显示它，S6 实现后可以删 */
  desc: string
}

/**
 * 6 个业务页面。
 *
 * 顺序 = 使用频率：仪表盘是落地页，任务中心是操作入口，
 * 请求审计是最少看的（P1），放最后。
 */
export const MENUS: MenuItem[] = [
  {
    path: '/dashboard',
    title: '仪表盘',
    icon: 'DataLine',
    desc: '4 个指标卡（图书总数 / 分类数 / 任务数 / 今日新增）+ 4 张图（分类分布 / 评分分布 / 出版年份 / 近 7 天采集量）',
  },
  {
    path: '/books',
    title: '图书库',
    icon: 'Reading',
    desc: '筛选栏 + 12 列表格 + 分页 + 详情抽屉（46 字段分组）+ 批量删除',
  },
  {
    path: '/tasks',
    title: '任务中心',
    icon: 'List',
    desc: '任务列表 + 新建向导 + 详情（手动刷新进度）+ 启动 / 暂停 / 恢复 / 取消 / 重试',
  },
  {
    path: '/taxonomy',
    title: '分类榜单',
    icon: 'Collection',
    desc: '21 个分类 + 7 个榜单的字典维护（新增 / 编辑 / 启停 / 删除）',
  },
  {
    path: '/cursors',
    title: '采集游标',
    icon: 'Aim',
    desc: '每个采集目标的断点位置（maxIndex），支持重置与指定起点',
  },
  {
    path: '/requests',
    title: '请求审计',
    icon: 'Document',
    desc: '每一次对微信读书接口的 HTTP 请求（状态码 / 耗时 / 总条数 / 是否还有下一页）',
  },
]

/** 按路径查菜单项。面包屑用它取标题 */
export function findMenu(path: string): MenuItem | undefined {
  return MENUS.find((item) => item.path === path)
}
