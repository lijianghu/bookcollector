<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { batchDeleteBooks, deleteBook, getBook, pageBooks } from '@/api/book'
import { listTaxonomy } from '@/api/taxonomy'
import ProTable from '@/components/ProTable.vue'
import SearchForm from '@/components/SearchForm.vue'
import DetailDrawer from '@/components/DetailDrawer.vue'
import { useColumnPrefs, type ColumnPref } from '@/composables/useColumnPrefs'
import { buildBookDetailSections, countDetailItems } from './detail-sections'
import BookEditDialog from './BookEditDialog.vue'
import { dash, formatDate, formatNumber, formatPublishDate } from '@/utils/format'
import type { Book, BookQuery, BookSortField, TaxonomyItem } from '@/types'

/**
 * 图书库（S6.2）。
 *
 * <h3>R17 在这里兑现：44 字段的宽表不在列表页全展示</h3>
 * 接口一次返 54 个键。全铺到表格里既看不清也会卡（`el-table` 没有虚拟滚动）。
 * 所以列表只放 10 列左右，全部字段进详情抽屉（分 5 组折叠）。
 *
 * <h3>列配置为什么值得做</h3>
 * 不是「锦上添花的设置项」—— 11 列表格在 1440px 屏上放不下，必然横向滚动。
 * 与其让用户忍着滚，不如让他把不关心的列关掉。配置存 localStorage，
 * 按页面 key 隔离（`bookcollector_columns_books`）。
 *
 * <h3>三处「看起来多余但必须有」的处理</h3>
 * 1. **页码越界纠正**：在第 3 页删光之后 `page=3` 会返回空列表，
 *    用户以为「数据没了」。加载后如果 `page > 总页数` 就退回最后一页重来一次。
 * 2. **编辑成功后整页重拉，而不是就地改行**：因为改的可能正是**当前筛选字段**
 *    （比如正按作者筛选，把作者改成了别的），就地改行会让列表和筛选条件自相矛盾。
 * 3. **采集来源两个条件必须都选**：后端 `resolveSourceKey()` 只要缺一个就返回 null
 *    表示「不筛来源」—— 只选类型不选目标会**静默失效**，所以切换类型时清空目标。
 */

// ---------------------------------------------------------------------------
// 列配置
// ---------------------------------------------------------------------------

/**
 * 列定义。
 *
 * <h3>⚠️ 默认可见列的宽度总和必须放得下表格容器</h3>
 * 1440 宽的屏上，扣掉侧边栏 210 + 页面/卡片内边距 64，表格容器约 **1146px**。
 * 列宽总和一旦超过它，`el-table` 会横向滚动 —— 而 Element Plus 给表格的
 * 横向滚动条是 `el-scrollbar__wrap--hidden-default`（**默认隐藏**），
 * 用户不会知道右边还有列，只会觉得「采集来源那一列怎么没了」。
 *
 * 所以这里把「最近采集」「采集来源」设成默认不显示，并把其余列收紧到
 * 总和 ≈ 1118px，留出余量。想要更多列就去「列配置」里打开 ——
 * 那时用户知道自己在做什么，也愿意横向滚。
 *
 * 这条约定有验收守着：`tools/s6-acceptance.mjs` 里断言
 * 「默认列下表格不出现横向滚动」+「表头没有一列换行」。
 */
const COLUMNS: ColumnPref[] = [
  { key: 'cover', label: '封面' },
  // 书名和操作固定：没有书名的表格没法认，没有操作列就点不动
  { key: 'title', label: '书名', fixed: true },
  { key: 'author', label: '作者' },
  { key: 'category', label: '平台分类' },
  { key: 'newRating', label: '推荐值' },
  { key: 'newRatingCount', label: '评价人数' },
  { key: 'readingCount', label: '阅读人数' },
  { key: 'publishTime', label: '出版时间' },
  // 下面两列默认不显示：它们是「想深入看的时候才需要」的信息，但很占地方
  { key: 'lastCollectedAt', label: '最近采集', defaultVisible: false },
  { key: 'collectSource', label: '采集来源', defaultVisible: false },
  { key: 'actions', label: '操作', fixed: true },
]

const cols = useColumnPrefs('books', COLUMNS)
const columnPanelVisible = ref(false)

// ---------------------------------------------------------------------------
// 列表状态
// ---------------------------------------------------------------------------

const loading = ref(false)
const rows = ref<Book[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const selectedRows = ref<Book[]>([])
const deleting = ref(false)

/** 排序。默认与后端 `BookQuery` 的默认值一致：推荐值降序 */
const sortField = ref<BookSortField>('newRating')
const sortAsc = ref(false)

/**
 * 查询条件。与 `BookQuery` 的 12 个参数一一对应。
 *
 * `categoryId` 是**数字**（后端按 `categories.categoryId` 精确匹配），
 * 而 `targetId` 是**字符串**（分类的 code 是 "300000"，榜单的 code 是 "rising"）。
 * 两个字段类型不一样，是后端的实际契约，不是笔误。
 */
const query = reactive<{
  categoryId?: number
  minRating?: number
  maxRating?: number
  minReadingCount?: number
  targetType: '' | 'CATEGORY' | 'RANKING'
  targetId?: string
  author?: string
  minPublishTime?: string
  maxPublishTime?: string
}>({
  targetType: '',
})

/** 字典：分类与榜单。用于筛选栏的下拉框，也用于把 sourceKey 翻译成人话 */
const categories = ref<TaxonomyItem[]>([])
const rankings = ref<TaxonomyItem[]>([])

const categoryOptions = computed(() =>
  categories.value.map((c) => ({
    value: Number(c.code),
    label: `${c.name}（${c.code}）`,
  })),
)

/** 采集来源的目标候选，跟着 targetType 走 */
const targetOptions = computed(() => {
  const source = query.targetType === 'RANKING' ? rankings.value : categories.value
  return source.map((t) => ({ value: t.code, label: `${t.name}（${t.code}）` }))
})

/**
 * 切换采集来源类型时清空已选目标。
 *
 * 不清的话会出这种事：先选「分类 + 文学」，再切成「榜单」——
 * 目标还留着 "300000"，而后端会拿 `ranking:300000` 去匹配，永远 0 条。
 * 更糟的是它不报错，看起来就像「这个榜单一本书都没有」。
 */
function handleTargetTypeChange(): void {
  query.targetId = undefined
}

/** `sourceKey`（"category:300000"）→ 「分类 · 文学」这样的人话 */
function sourceText(sourceKey?: string | null): string {
  if (!sourceKey) {
    return '-'
  }
  const idx = sourceKey.indexOf(':')
  const type = idx > 0 ? sourceKey.slice(0, idx).toUpperCase() : ''
  const code = idx > 0 ? sourceKey.slice(idx + 1) : sourceKey
  const dict = type === 'RANKING' ? rankings.value : categories.value
  const hit = dict.find((t) => t.code === code)
  const typeName = type === 'RANKING' ? '榜单' : '分类'
  return hit ? `${typeName} · ${hit.name}` : sourceKey
}

function sourceList(sourceKeys?: string[] | null): string[] {
  if (!Array.isArray(sourceKeys) || sourceKeys.length === 0) {
    return []
  }
  return sourceKeys.map(sourceText)
}

// ---------------------------------------------------------------------------
// 加载
// ---------------------------------------------------------------------------

/** 防止「页码越界纠正」反复触发 */
let correcting = false

async function load(): Promise<void> {
  loading.value = true
  try {
    const payload: BookQuery = {
      ...query,
      sortField: sortField.value,
      asc: sortAsc.value,
      page: page.value,
      size: size.value,
    }
    const result = await pageBooks(payload)
    rows.value = result.list
    total.value = result.total

    // 页码越界纠正：在第 3 页删光后 page=3 会返回空列表，用户以为数据没了。
    // 退回最后一页重来一次（只做一次，避免死循环）。
    const lastPage = Math.max(1, result.totalPages || 1)
    if (!correcting && result.list.length === 0 && page.value > lastPage) {
      correcting = true
      page.value = lastPage
      await load()
      correcting = false
    }
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadDict(): Promise<void> {
  try {
    const [cats, ranks] = await Promise.all([
      listTaxonomy('CATEGORY'),
      listTaxonomy('RANKING'),
    ])
    categories.value = cats
    rankings.value = ranks
  } catch {
    // 字典拉不到不影响列表本身，下拉框空着即可
    categories.value = []
    rankings.value = []
  }
}

/**
 * 合并同一轮事件里的多次加载请求。
 *
 * 改每页条数时 ProTable 会**同步**先 emit `update:size` 再 emit `update:page(1)`，
 * 两个事件各自都会请求一次数据 —— 不合并就是两次一模一样的请求，
 * 而且它们的**返回顺序不保证**：后发的先回来就会把先发的结果覆盖掉。
 *
 * 用微任务攒一下：两个事件在同一个调用栈里跑完，微任务才执行，于是只请求一次。
 */
let reloadScheduled = false
function scheduleReload(): void {
  if (reloadScheduled) {
    return
  }
  reloadScheduled = true
  void Promise.resolve().then(() => {
    reloadScheduled = false
    void load()
  })
}

function handleSearch(): void {
  page.value = 1
  scheduleReload()
}

function handleReset(): void {
  query.categoryId = undefined
  query.minRating = undefined
  query.maxRating = undefined
  query.minReadingCount = undefined
  query.targetType = ''
  query.targetId = undefined
  query.author = undefined
  query.minPublishTime = undefined
  query.maxPublishTime = undefined
  page.value = 1
  scheduleReload()
}

function handlePageChange(next: number): void {
  page.value = next
  scheduleReload()
}

function handleSizeChange(next: number): void {
  size.value = next
  // ProTable 也会 emit update:page(1)，但事件顺序不保证 —— 这里自己也算一次，
  // 免得出现「page 还是 5、size 变成 100」这种越界请求
  page.value = 1
  scheduleReload()
}

function handleSelectionChange(list: unknown[]): void {
  selectedRows.value = list as Book[]
}

/** 排序字段 → 表头的中文名，给工具栏那行说明用 */
const SORT_LABELS: Record<BookSortField, string> = {
  newRating: '推荐值',
  newRatingCount: '评价人数',
  readingCount: '阅读人数',
  publishTime: '出版时间',
  lastCollectedAt: '最近采集',
}

/**
 * el-table 的自定义排序回调。
 *
 * `order` 是 `'ascending' | 'descending' | null`，`null` 表示用户取消了排序 ——
 * 这时要回到后端默认的「推荐值降序」，而不是「保持上一次的字段、只是不排」。
 */
function handleSortChange({ prop, order }: { prop: string | null; order: string | null }): void {
  if (!order || !prop) {
    sortField.value = 'newRating'
    sortAsc.value = false
  } else {
    sortField.value = prop as BookSortField
    sortAsc.value = order === 'ascending'
  }
  page.value = 1
  scheduleReload()
}

// ---------------------------------------------------------------------------
// 详情抽屉
// ---------------------------------------------------------------------------

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailBook = ref<Book | null>(null)

const detailSections = computed(() =>
  detailBook.value ? buildBookDetailSections(detailBook.value) : [],
)
const detailItemCount = computed(() => countDetailItems(detailSections.value))

/**
 * 打开详情时**重新拉一次详情接口**，而不是直接用列表里那一行。
 *
 * 列表里的 `Book` 字段其实也是全的（54 个键都在），但可能是几分钟前拉的。
 * 重新拉一次的成本是一个请求，换来的是「抽屉里看到的和库里一致」。
 */
async function openDetail(book: Book): Promise<void> {
  detailVisible.value = true
  detailLoading.value = true
  detailBook.value = book
  try {
    detailBook.value = await getBook(book.bookId)
  } catch {
    // 拉失败就继续用列表里那份，至少不是空白
  } finally {
    detailLoading.value = false
  }
}

// ---------------------------------------------------------------------------
// 编辑
// ---------------------------------------------------------------------------

const editVisible = ref(false)
const editBook = ref<Book | null>(null)

/**
 * 打开编辑弹窗。
 *
 * 优先用抽屉里那份（它是刚从详情接口拉的，最新），其次才用列表那一行。
 * 不这么做的话会出现「抽屉里显示作者是 A，点编辑打开的表单里是 B」。
 */
function openEdit(book: Book): void {
  editBook.value =
    detailBook.value && detailBook.value.bookId === book.bookId ? detailBook.value : book
  editVisible.value = true
}

/**
 * 保存成功后的处理：重拉列表 + 刷新抽屉。
 *
 * 为什么不是「就地替换那一行」：改的可能正是当前筛选字段（比如正按作者筛选，
 * 把作者改掉了）。就地改行会让列表里留着一行**不满足筛选条件**的数据，
 * 而用户完全看不出来 —— 下次刷新它就凭空消失。
 * 重拉一次代价很小，换来「列表永远和筛选条件自洽」。
 */
async function handleSaved(updated: Book): Promise<void> {
  if (detailBook.value && detailBook.value.bookId === updated.bookId) {
    detailBook.value = updated
  }
  await load()
}

// ---------------------------------------------------------------------------
// 删除
// ---------------------------------------------------------------------------

async function confirmDelete(book: Book): Promise<void> {
  try {
    // ⚠️ ElMessageBox 默认不解析 HTML，`\n` 和 markdown 都不会换行/加粗 ——
    // 想用富文本得开 dangerouslyUseHTMLString，而这本书的书名是**接口数据**，
    // 拿它拼 HTML 就是一个 XSS 面。所以这里只说人话，不用富文本。
    await ElMessageBox.confirm(
      `确定删除《${book.title ?? book.bookId}》吗？` +
        `注意：下次采集到这本书时它会被重新写入，删除不是永久拉黑。`,
      '删除图书',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    // 用户点了取消
    return
  }
  deleting.value = true
  try {
    const result = await deleteBook(book.bookId)
    ElMessage.success(`已删除 ${result.deleted} 本`)
    if (detailBook.value?.bookId === book.bookId) {
      detailVisible.value = false
      detailBook.value = null
    }
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    deleting.value = false
  }
}

async function confirmBatchDelete(): Promise<void> {
  const count = selectedRows.value.length
  if (count === 0) {
    return
  }
  const titles = selectedRows.value
    .slice(0, 5)
    .map((b) => `《${b.title ?? b.bookId}》`)
    .join('、')
  const more = count > 5 ? ` 等 ${count} 本` : ''
  try {
    await ElMessageBox.confirm(
      `确定删除 ${titles}${more}吗？共 ${count} 本。` +
        `注意：下次采集到这些书时它们会被重新写入。`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  deleting.value = true
  try {
    const result = await batchDeleteBooks(selectedRows.value.map((b) => b.bookId))
    // 返回的是**实际**删除数，可能小于请求数（有的 bookId 已经不在了）
    const missed = result.requested - result.deleted
    ElMessage.success(
      missed > 0
        ? `请求删除 ${result.requested} 本，实际删除 ${result.deleted} 本（${missed} 本已不存在）`
        : `已删除 ${result.deleted} 本`,
    )
    selectedRows.value = []
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    deleting.value = false
  }
}

// ---------------------------------------------------------------------------

onMounted(() => {
  loadDict()
  load()
})
</script>

<template>
  <div class="page">
    <!-- ================= 筛选栏（12 个查询参数） ================= -->
    <SearchForm
      :model="query"
      :loading="loading"
      :columns="4"
      label-width="100px"
      @search="handleSearch"
      @reset="handleReset"
    >
      <el-form-item label="平台分类">
        <el-select v-model="query.categoryId" clearable filterable placeholder="全部">
          <el-option
            v-for="opt in categoryOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="推荐值区间">
        <div class="range">
          <el-input-number
            v-model="query.minRating"
            :min="0"
            :max="1000"
            :controls="false"
            placeholder="下限"
          />
          <span class="range-sep">~</span>
          <el-input-number
            v-model="query.maxRating"
            :min="0"
            :max="1000"
            :controls="false"
            placeholder="上限"
          />
        </div>
      </el-form-item>

      <el-form-item label="阅读人数">
        <el-input-number
          v-model="query.minReadingCount"
          :min="0"
          :controls="false"
          placeholder="下限，不限则空"
        />
      </el-form-item>

      <el-form-item label="作者">
        <el-input v-model="query.author" clearable placeholder="精确匹配，不是模糊搜索" />
      </el-form-item>

      <el-form-item label="采集来源">
        <div class="range">
          <el-select
            v-model="query.targetType"
            clearable
            placeholder="类型"
            class="source-type"
            @change="handleTargetTypeChange"
          >
            <el-option label="分类" value="CATEGORY" />
            <el-option label="榜单" value="RANKING" />
          </el-select>
          <el-select
            v-model="query.targetId"
            clearable
            filterable
            :disabled="!query.targetType"
            placeholder="目标"
            class="source-target"
          >
            <el-option
              v-for="opt in targetOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </div>
      </el-form-item>

      <el-form-item label="出版时间从">
        <el-input v-model="query.minPublishTime" clearable placeholder="2015 / 2015-06" />
      </el-form-item>

      <el-form-item label="出版时间到">
        <el-input v-model="query.maxPublishTime" clearable placeholder="2023 / 2023-12" />
      </el-form-item>

      <div class="query-hint">
        共 12 个查询参数，条件之间是「与」的关系。作者是精确匹配（本期不做模糊搜索）；
        采集来源要「类型 + 目标」都选才生效。
      </div>
    </SearchForm>

    <!-- ================= 表格 ================= -->
    <el-card shadow="never" class="table-card">
      <ProTable
        :data="rows"
        :loading="loading"
        :total="total"
        :page="page"
        :size="size"
        :page-sizes="[20, 50, 100]"
        row-key="bookId"
        selectable
        :default-sort="{ prop: 'newRating', order: 'descending' }"
        max-height="calc(100vh - 430px)"
        empty-text="没有符合条件的图书"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
        @selection-change="handleSelectionChange"
        @sort-change="handleSortChange"
      >
        <template #toolbar>
          <el-button type="primary" :loading="loading" @click="load">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>

          <el-popover v-model:visible="columnPanelVisible" placement="bottom-start" :width="230" trigger="click">
            <template #reference>
              <el-button>
                <el-icon><Setting /></el-icon>
                列配置
              </el-button>
            </template>
            <div class="col-panel">
              <el-checkbox-group v-model="cols.visible.value">
                <el-checkbox
                  v-for="col in cols.columns"
                  :key="col.key"
                  :value="col.key"
                  :disabled="col.fixed"
                  class="col-item"
                >
                  {{ col.label }}
                  <span v-if="col.fixed" class="col-fixed">必显</span>
                </el-checkbox>
              </el-checkbox-group>
              <el-button link type="primary" class="col-reset" @click="cols.reset()">
                恢复默认
              </el-button>
            </div>
          </el-popover>

          <el-button
            v-if="selectedRows.length > 0"
            type="danger"
            :loading="deleting"
            @click="confirmBatchDelete"
          >
            <el-icon><Delete /></el-icon>
            批量删除（{{ selectedRows.length }}）
          </el-button>

          <span class="toolbar-info">
            共 {{ formatNumber(total) }} 本 · 本页 {{ rows.length }} 条 ·
            排序：{{ SORT_LABELS[sortField] }} {{ sortAsc ? '升序' : '降序' }}
          </span>
        </template>

        <el-table-column v-if="cols.isVisible('cover')" label="封面" width="68" align="center">
          <template #default="{ row }">
            <el-image
              v-if="row.cover"
              :src="row.cover"
              :preview-src-list="[row.cover]"
              fit="cover"
              lazy
              preview-teleported
              class="cover"
            />
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('title')"
          prop="title"
          label="书名"
          min-width="180"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="openDetail(row)">
              {{ row.title || '（无书名）' }}
            </el-link>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('author')"
          prop="author"
          label="作者"
          width="120"
          show-overflow-tooltip
        >
          <template #default="{ row }">{{ dash(row.author) }}</template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('category')"
          prop="category"
          label="平台分类"
          min-width="150"
          show-overflow-tooltip
        >
          <template #default="{ row }">{{ dash(row.category) }}</template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('newRating')"
          prop="newRating"
          label="推荐值"
          width="88"
          align="right"
          sortable="custom"
        >
          <template #default="{ row }">
            <span class="num">{{ dash(row.newRating) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('newRatingCount')"
          prop="newRatingCount"
          label="评价人数"
          width="100"
          align="right"
          sortable="custom"
        >
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.newRatingCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('readingCount')"
          prop="readingCount"
          label="阅读人数"
          width="100"
          align="right"
          sortable="custom"
        >
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.readingCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('publishTime')"
          prop="publishTime"
          label="出版时间"
          width="100"
          sortable="custom"
        >
          <!-- 库里存在 "2022" 这种只有年份的脏值，所以不能按时间格式化，只能原样显示 -->
          <template #default="{ row }">{{ formatPublishDate(row.publishTime) }}</template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('lastCollectedAt')"
          prop="lastCollectedAt"
          label="最近采集"
          width="112"
          sortable="custom"
        >
          <template #default="{ row }">{{ formatDate(row.lastCollectedAt) }}</template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('collectSource')"
          label="采集来源"
          min-width="150"
        >
          <template #default="{ row }">
            <template v-if="sourceList(row.collectSource).length">
              <el-tag
                v-for="s in sourceList(row.collectSource)"
                :key="s"
                size="small"
                effect="plain"
                class="source-tag"
              >
                {{ s }}
              </el-tag>
            </template>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('actions')"
          label="操作"
          width="168"
          fixed="right"
          align="center"
        >
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" :disabled="deleting" @click="confirmDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </ProTable>
    </el-card>

    <!-- ================= 详情抽屉 ================= -->
    <DetailDrawer
      v-model="detailVisible"
      :title="detailBook?.title || '图书详情'"
      :subtitle="detailBook ? `${detailBook.author || '佚名'} · 推荐值 ${dash(detailBook.newRating)} · ${detailItemCount} 个字段` : ''"
      :sections="detailSections"
      :loading="detailLoading"
      width="760px"
    >
      <template #footer>
        <el-button v-if="detailBook" @click="openEdit(detailBook)">
          <el-icon><Edit /></el-icon>
          编辑
        </el-button>
        <el-button
          v-if="detailBook"
          type="danger"
          :loading="deleting"
          @click="confirmDelete(detailBook)"
        >
          <el-icon><Delete /></el-icon>
          删除
        </el-button>
      </template>
    </DetailDrawer>

    <!-- ================= 编辑弹窗 ================= -->
    <BookEditDialog v-model="editVisible" :book="editBook" @saved="handleSaved" />
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.table-card :deep(.el-card__body) {
  padding: 16px;
}

/* 区间输入：两个数字框 + 中间的波浪号 */
.range {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
}
.range :deep(.el-input-number) {
  flex: 1;
  min-width: 0;
}
.range-sep {
  color: #909399;
  flex-shrink: 0;
}
.source-type {
  width: 104px;
  flex-shrink: 0;
}
.source-target {
  flex: 1;
  min-width: 0;
}

.query-hint {
  grid-column: 1 / -1;
  margin: -8px 0 8px;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}

.toolbar-info {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

.cover {
  width: 44px;
  height: 60px;
  border-radius: 3px;
  background: #f5f7fa;
  vertical-align: middle;
}

.source-tag {
  margin: 0 4px 2px 0;
}

.col-panel {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.col-item {
  margin-right: 0;
  height: 26px;
}
.col-fixed {
  margin-left: 6px;
  font-size: 11px;
  color: #c0c4cc;
}
.col-reset {
  align-self: flex-start;
  margin-top: 4px;
}
</style>
