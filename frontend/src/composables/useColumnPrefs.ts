/**
 * 表格列显隐偏好（存 localStorage）。
 *
 * <h3>为什么抽出来</h3>
 * 图书库 / 分类榜单 / 采集游标 / 请求审计 都要「勾选显示哪些列」，
 * 而且都要处理同样三个细节：持久化、旧配置里已经不存在的列、
 * 以及「必显列不能被关掉」。写四遍必然会漏掉其中一个。
 *
 * <h3>为什么不直接存整份配置对象</h3>
 * 存 `{ title: true, author: false }` 看起来更直观，但一旦某列被改名或删掉，
 * 旧配置里的键会永远留在 localStorage 里，越积越多。
 * 这里存**可见列的 key 数组**，加载时跟当前列清单求交集 —— 脏数据自动被清掉。
 */

import { ref, watch, type Ref } from 'vue'

export interface ColumnPref {
  /** 列的唯一键，和模板里的 `v-if` 对应 */
  key: string
  /** 列配置面板里显示的名字 */
  label: string
  /** 默认是否显示。不传 = 显示 */
  defaultVisible?: boolean
  /** 必显列：不允许被关掉（比如「书名」「操作」）。列配置面板里显示成禁用状态 */
  fixed?: boolean
}

export interface ColumnPrefs {
  /** 当前可见列的 key 列表 */
  visible: Ref<string[]>
  /** 某一列是否显示 */
  isVisible: (key: string) => boolean
  /** 恢复默认 */
  reset: () => void
  /** 全部列（含不可见的），给列配置面板渲染用 */
  columns: ColumnPref[]
  /** 必显列 */
  fixedKeys: string[]
}

const STORAGE_PREFIX = 'bookcollector_columns_'

export function useColumnPrefs(storageKey: string, columns: ColumnPref[]): ColumnPrefs {
  const fullKey = `${STORAGE_PREFIX}${storageKey}`
  const allKeys = columns.map((c) => c.key)
  const fixedKeys = columns.filter((c) => c.fixed).map((c) => c.key)

  /** 默认可见列 = 没写 `defaultVisible: false` 的那些 */
  function defaults(): string[] {
    return columns.filter((c) => c.defaultVisible !== false).map((c) => c.key)
  }

  function load(): string[] {
    let raw: string | null = null
    try {
      raw = localStorage.getItem(fullKey)
    } catch {
      // 隐私模式下 localStorage 可能直接抛异常
      return defaults()
    }
    if (!raw) {
      return defaults()
    }
    let parsed: unknown
    try {
      parsed = JSON.parse(raw)
    } catch {
      // 手改坏了就回默认，不要让页面白屏
      return defaults()
    }
    if (!Array.isArray(parsed)) {
      return defaults()
    }
    // 只保留「当前还存在」的列 —— 旧配置里被删掉的列在这里被清掉
    const kept = parsed.filter(
      (k): k is string => typeof k === 'string' && allKeys.includes(k),
    )
    // 必显列强制带上（防止旧配置里把「书名」关掉了，表格变成没法认）
    const merged = Array.from(new Set([...kept, ...fixedKeys]))
    // 交出来是空的说明配置已经没意义了（比如所有列都改了名），回默认
    return merged.length > 0 ? merged : defaults()
  }

  const visible = ref<string[]>(load())

  watch(
    visible,
    (next) => {
      try {
        localStorage.setItem(fullKey, JSON.stringify(next))
      } catch {
        // 存不进去也不影响使用，忽略
      }
    },
    { deep: true },
  )

  function isVisible(key: string): boolean {
    return visible.value.includes(key)
  }

  function reset(): void {
    visible.value = defaults()
  }

  return { visible, isVisible, reset, columns, fixedKeys }
}
