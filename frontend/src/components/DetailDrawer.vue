<script setup lang="ts">
import { ref, watch } from 'vue'
import { dash, formatDateTime } from '@/utils/format'

/**
 * 详情抽屉（数据驱动，按分组折叠）。
 *
 * <h3>为什么是数据驱动而不是插槽</h3>
 * `Book` 有 46 个字段，分成 5 组。用插槽的话页面里要写 46 个 `<el-descriptions-item>`
 * —— 三百多行纯样板，而且字段名/中文名散在模板里，改一个字段要翻半天。
 * 用「配置数组」写，字段清单变成一个可以直接对照 Java 实体的数组，一眼能看出漏没漏。
 *
 * <h3>为什么默认折叠</h3>
 * 46 个字段全展开要滚三屏，用户打开抽屉只想看「这本书是什么」——
 * 所以第一组（基本信息）默认展开，其余折叠。这也正好兑现了 R17
 * （宽表不在列表页全展示，全部字段放抽屉里）。
 */

/** 详情值的类型。数组是给「标签组」用的（分类标签、采集来源） */
export type DetailValue = string | number | boolean | null | Array<string | number>

export interface DetailItem {
  /** 中文标签 */
  label: string
  /** 值。`null` / `undefined` / `''` 统一显示成 `-` */
  value?: DetailValue
  /** 值是不是时间（UTC ISO 字符串），是的话格式化成本地时间 */
  time?: boolean
  /** 值是不是图片 URL，是的话渲染成缩略图 */
  image?: boolean
  /** 值是不是数组，是的话渲染成标签组 */
  tags?: boolean
  /** 占几列（默认 1，抽屉宽度下 2 列布局） */
  span?: number
  /** 等宽字体（URL / ID / traceId 之类） */
  mono?: boolean
  /** 值很长（简介、备注），整行展示并保留换行 */
  block?: boolean
}

export interface DetailSection {
  title: string
  items: DetailItem[]
}

const props = withDefaults(
  defineProps<{
    /** 是否显示。配合 `@update:modelValue` 使用 */
    modelValue: boolean
    title?: string
    /** 标题下方的一行副标题（比如作者 / 采集来源） */
    subtitle?: string
    sections: DetailSection[]
    loading?: boolean
    width?: string
  }>(),
  {
    title: '详情',
    subtitle: '',
    loading: false,
    width: '680px',
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

/**
 * 展开的分组。
 *
 * ⚠️ 必须是 `ref` 而不是 `computed` —— `v-model` 要往它写回值，
 * 而 computed 没有 setter，控制台会报 "Write operation failed: computed value is readonly"，
 * 表现成「点折叠标题没反应」。
 *
 * 默认展开第一组：用户打开抽屉最想看的就是「这是什么」。
 */
const activeNames = ref<string[]>([])

// 每次打开抽屉（或换了一条数据）都重置成「只展开第一组」。
// 不重置的话，看过一个 46 字段的图书之后再打开另一本，上一本的展开状态会留着 ——
// 用户会以为「这次怎么默认全展开了」
watch(
  () => [props.modelValue, props.title] as const,
  ([visible]) => {
    if (visible) {
      activeNames.value = props.sections.length > 0 ? [props.sections[0].title] : []
    }
  },
  { immediate: true },
)

function handleVisibleChange(value: boolean): void {
  emit('update:modelValue', value)
}

/** 把一个 item 的原始值渲染成可显示的字符串 */
function renderValue(item: DetailItem): string {
  if (item.time) {
    return formatDateTime(item.value as string | number | null)
  }
  return dash(item.value as string | number | boolean | null)
}

/** 数组值转成字符串数组（过滤掉空值） */
function toTags(value: DetailItem['value']): string[] {
  if (!Array.isArray(value)) {
    return []
  }
  return (value as unknown[]).map((v) => String(v)).filter((v) => v !== '')
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    :size="width"
    direction="rtl"
    destroy-on-close
    @update:model-value="handleVisibleChange"
  >
    <template #header>
      <div class="drawer-header">
        <span class="drawer-title">{{ title }}</span>
        <span v-if="subtitle" class="drawer-subtitle">{{ subtitle }}</span>
      </div>
    </template>

    <el-skeleton v-if="loading" :rows="8" animated />

    <el-collapse v-else v-model="activeNames">
      <el-collapse-item
        v-for="section in sections"
        :key="section.title"
        :name="section.title"
      >
        <template #title>
          <span class="section-title">{{ section.title }}</span>
          <span class="section-count">{{ section.items.length }} 项</span>
        </template>

        <el-descriptions :column="2" border size="small" class="desc">
          <el-descriptions-item
            v-for="item in section.items"
            :key="item.label"
            :label="item.label"
            :span="item.block ? 2 : item.span ?? 1"
          >
            <!-- 封面：缩略图 + 可点开的原图 -->
            <template v-if="item.image">
              <el-image
                v-if="item.value"
                :src="String(item.value)"
                :preview-src-list="[String(item.value)]"
                fit="cover"
                class="cover"
                preview-teleported
              />
              <span v-else>-</span>
            </template>

            <!-- 数组：标签组 -->
            <template v-else-if="item.tags">
              <template v-if="toTags(item.value).length">
                <el-tag
                  v-for="tag in toTags(item.value)"
                  :key="tag"
                  size="small"
                  effect="plain"
                  class="tag"
                >
                  {{ tag }}
                </el-tag>
              </template>
              <span v-else>-</span>
            </template>

            <!-- 长文本：保留换行 -->
            <span v-else-if="item.block" class="block-text">{{ renderValue(item) }}</span>

            <!-- 普通值 -->
            <span v-else :class="{ 'text-mono': item.mono }">{{ renderValue(item) }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </el-collapse-item>
    </el-collapse>

    <template #footer>
      <slot name="footer" />
    </template>
  </el-drawer>
</template>

<style scoped>
.drawer-header {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.drawer-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.drawer-subtitle {
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.section-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}
.section-count {
  margin-left: 8px;
  font-size: 12px;
  color: #a8abb2;
}

.desc :deep(.el-descriptions__label) {
  width: 120px;
  color: #606266;
  background: #fafafa;
}
.desc :deep(.el-descriptions__content) {
  word-break: break-all;
}

.cover {
  width: 66px;
  height: 92px;
  border-radius: 3px;
  background: #f5f7fa;
}

.tag {
  margin: 0 4px 4px 0;
}

.block-text {
  display: block;
  max-height: 200px;
  overflow-y: auto;
  line-height: 1.7;
  white-space: pre-wrap;
}
</style>
