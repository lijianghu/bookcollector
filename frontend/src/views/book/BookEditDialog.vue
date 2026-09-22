<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { updateBook } from '@/api/book'
import type { Book, BookUpdateRequest } from '@/types'

/**
 * 图书编辑弹窗。
 *
 * <h3>为什么只提交「改过的字段」</h3>
 * 后端 `PUT /api/books/{id}` 是**局部更新**：`BookService.update()` 用
 * `putIfNotNull()` 逐字段判空，只有传了非 `null` 的字段才会进 `$set`。
 *
 * 所以这里绝不能图省事把整个 `Book` 回传 —— 那等于把 `newRating`、
 * `readingCount`、`collectSource`、`firstCollectedAt` 这些**采集链路的账本**
 * 一起写回去（它们本来就不在白名单里，回传只是浪费 + 有覆盖风险）。
 * 而且「一次只改一个字段，结果整行被重写」会让日志没法看。
 *
 * <h3>「没改」和「清空」是两件事</h3>
 * - **文本字段**：清空 → 提交 `''`，真的把库里那个字段清掉（用户看得见结果）
 * - **数字 / 下拉字段**：清空 → **不提交**。因为后端用 `null` 表示「不改」，
 *   而 `0` 是一个有意义的真实值（价格 0 元 ≠ 价格未知），
 *   拿 `0` 当「空」会污染数据，拿 `null` 当「清空」又表达不出来。
 *   折中：留空 = 不修改，并在字段旁写明这条规则。
 *
 * <h3>为什么出版时间用文本框而不是 el-date-picker</h3>
 * `publishTime` 在库里是**字符串**（`"2022-08-01 00:00:00"`），后端
 * `BookQuery.resolveMaxPublishTime()` 靠**字典序**做区间比较，
 * 而且库里存在 `"2022"` 这种只有年份的脏值 —— `el-date-picker` 装不下「只有年份」，
 * 还会把值格式化成它自己的格式，一保存就把原本可比的时间格式改坏了。
 */

const props = defineProps<{
  modelValue: boolean
  /** 要编辑的图书 */
  book: Book | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved', book: Book): void
}>()

/**
 * 分类标签的本地行类型。
 *
 * 刻意用 `number`（可 undefined）而不是 `BookCategory` 的 `number | null`：
 * `el-input-number` 的 `modelValue` 类型是 `number | undefined`，
 * 传 `null` 过不了类型检查。反正提交时 undefined 和 null 都会被 JSON 丢掉，
 * 后端 `putIfNotNull` 也一视同仁。
 */
interface CategoryRow {
  categoryId?: number
  subCategoryId?: number
  categoryType?: number
  title?: string
}

/** 13 个白名单字段。与后端 `BookUpdateRequest` 一一对应 */
interface EditForm {
  title: string
  author: string
  translator: string
  intro: string
  category: string
  categories: CategoryRow[]
  publishTime: string
  language: string
  /** el-select 清空后给的是 undefined，所以类型里带上 */
  ispub?: number
  finished?: number
  free?: number
  price?: number
  originalPrice?: number
}

const formRef = ref<FormInstance>()
const saving = ref(false)

function emptyForm(): EditForm {
  return {
    title: '',
    author: '',
    translator: '',
    intro: '',
    category: '',
    categories: [],
    publishTime: '',
    language: '',
  }
}

const form = reactive<EditForm>(emptyForm())

/** 打开弹窗时的原始值快照，用来算「改了什么」 */
let snapshot: EditForm = emptyForm()

/** `null` / `undefined` → `''`，方便塞进输入框 */
function text(value?: string | null): string {
  return value === null || value === undefined ? '' : value
}

/**
 * 数组字段深拷贝。
 *
 * 必须同时做两件事：① 拷一份（直接引用的话改表单会连原书对象一起改掉）；
 * ② **统一用 undefined 表示空**（`?? undefined`）——
 * `sameCategories()` 是 JSON 字符串比较，如果一边是 `null` 一边是 `undefined`，
 * 序列化结果不同会误报「改过了」，用户一打开弹窗就被告知「将提交 1 个字段」。
 */
function cloneCategories(list?: { categoryId?: number | null; subCategoryId?: number | null; categoryType?: number | null; title?: string | null }[] | null): CategoryRow[] {
  if (!Array.isArray(list)) {
    return []
  }
  return list.map((c) => ({
    categoryId: c?.categoryId ?? undefined,
    subCategoryId: c?.subCategoryId ?? undefined,
    categoryType: c?.categoryType ?? undefined,
    title: c?.title ?? undefined,
  }))
}

function fillFrom(book: Book | null): void {
  if (!book) {
    Object.assign(form, emptyForm())
    snapshot = emptyForm()
    return
  }
  const next: EditForm = {
    title: text(book.title),
    author: text(book.author),
    translator: text(book.translator),
    intro: text(book.intro),
    category: text(book.category),
    categories: cloneCategories(book.categories),
    publishTime: text(book.publishTime),
    language: text(book.language),
    ispub: book.ispub ?? undefined,
    finished: book.finished ?? undefined,
    free: book.free ?? undefined,
    price: book.price ?? undefined,
    originalPrice: book.originalPrice ?? undefined,
  }
  Object.assign(form, next)
  snapshot = { ...next, categories: cloneCategories(next.categories) }
}

// 每次打开都重新灌一次，避免上一次编辑的残留
watch(
  () => [props.modelValue, props.book?.bookId] as const,
  ([visible]) => {
    if (visible) {
      fillFrom(props.book)
      formRef.value?.clearValidate()
    }
  },
  { immediate: true },
)

const rules: FormRules = {
  title: [{ required: true, message: '书名不能为空', trigger: 'blur' }],
}

/** 数组比较必须走 JSON —— 引用比较永远不相等，会误报「改过了」 */
function sameCategories(a: CategoryRow[], b: CategoryRow[]): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

/** `undefined`（el-select 清空）和 `null`（el-input-number 清空）统一成 null */
function norm(value?: number | null): number | null {
  return value === undefined || value === null ? null : value
}

/**
 * 只保留真正改过的字段。
 *
 * 数字 / 下拉字段的规则见文件头注释：归一化后是 `null` 的一律视为「不修改」，不进 payload。
 */
const payload = computed<BookUpdateRequest>(() => {
  const out: BookUpdateRequest = {}
  if (form.title !== snapshot.title) {
    out.title = form.title
  }
  if (form.author !== snapshot.author) {
    out.author = form.author
  }
  if (form.translator !== snapshot.translator) {
    out.translator = form.translator
  }
  if (form.intro !== snapshot.intro) {
    out.intro = form.intro
  }
  if (form.category !== snapshot.category) {
    out.category = form.category
  }
  if (!sameCategories(form.categories, snapshot.categories)) {
    out.categories = form.categories
  }
  if (form.publishTime !== snapshot.publishTime) {
    out.publishTime = form.publishTime
  }
  if (form.language !== snapshot.language) {
    out.language = form.language
  }

  const ispub = norm(form.ispub)
  if (ispub !== null && ispub !== norm(snapshot.ispub)) {
    out.ispub = ispub
  }
  const finished = norm(form.finished)
  if (finished !== null && finished !== norm(snapshot.finished)) {
    out.finished = finished
  }
  const free = norm(form.free)
  if (free !== null && free !== norm(snapshot.free)) {
    out.free = free
  }
  const price = norm(form.price)
  if (price !== null && price !== norm(snapshot.price)) {
    out.price = price
  }
  const originalPrice = norm(form.originalPrice)
  if (originalPrice !== null && originalPrice !== norm(snapshot.originalPrice)) {
    out.originalPrice = originalPrice
  }
  return out
})

const FIELD_LABELS: Record<string, string> = {
  title: '书名',
  author: '作者',
  translator: '译者',
  intro: '简介',
  category: '平台分类',
  categories: '分类标签',
  publishTime: '出版时间',
  language: '语言',
  ispub: '是否出版',
  finished: '是否完结',
  free: '是否免费',
  price: '价格',
  originalPrice: '原价',
}

/** 改过的字段名（给「将提交 N 个字段」这行提示用） */
const changedLabels = computed<string[]>(() =>
  Object.keys(payload.value).map((k) => FIELD_LABELS[k] ?? k),
)

function handleClose(): void {
  emit('update:modelValue', false)
}

function addCategory(): void {
  form.categories.push({ categoryType: 0, title: '' })
}

function removeCategory(index: number): void {
  form.categories.splice(index, 1)
}

async function submit(): Promise<void> {
  if (!props.book) {
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (Object.keys(payload.value).length === 0) {
    // 后端对空 payload 会返 code=400「没有需要修改的字段」，但那是**错误**。
    // 用户其实什么都没做错，这里直接给提示更友好。
    ElMessage.warning('没有需要保存的修改')
    return
  }

  saving.value = true
  try {
    const updated = await updateBook(props.book.bookId, payload.value)
    ElMessage.success(`已保存 ${changedLabels.value.length} 个字段`)
    emit('saved', updated)
    emit('update:modelValue', false)
  } catch {
    // 拦截器已经弹过后端的 message，这里不重复
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="编辑图书"
    width="720px"
    top="6vh"
    destroy-on-close
    @update:model-value="handleClose"
  >
    <el-alert type="info" :closable="false" class="tip">
      <template #title>
        只提交改过的字段。后端是局部更新（只写传了的字段），所以采集链路自己的字段
        （推荐值 / 阅读人数 / 采集来源 / 采集时间）在这里改不了。
      </template>
    </el-alert>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="88px" class="form">
      <el-form-item label="书名" prop="title">
        <el-input v-model="form.title" clearable placeholder="书名" />
      </el-form-item>

      <div class="row">
        <el-form-item label="作者">
          <el-input v-model="form.author" clearable placeholder="作者" />
        </el-form-item>
        <el-form-item label="译者">
          <el-input v-model="form.translator" clearable placeholder="译者" />
        </el-form-item>
      </div>

      <el-form-item label="平台分类">
        <el-input v-model="form.category" clearable placeholder="形如：精品小说-社会小说" />
      </el-form-item>

      <el-form-item label="分类标签">
        <div class="cat-list">
          <div v-for="(cat, index) in form.categories" :key="index" class="cat-row">
            <el-input-number
              v-model="cat.categoryId"
              :controls="false"
              placeholder="categoryId"
              class="cat-num"
            />
            <el-input-number
              v-model="cat.subCategoryId"
              :controls="false"
              placeholder="subCategoryId"
              class="cat-num"
            />
            <el-input-number
              v-model="cat.categoryType"
              :controls="false"
              placeholder="type"
              class="cat-num cat-num-sm"
            />
            <el-input
              v-model="cat.title"
              placeholder="标签名，如 文学-古典文学"
              class="cat-title"
            />
            <el-button link type="danger" @click="removeCategory(index)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
          <el-button link type="primary" @click="addCategory">
            <el-icon><Plus /></el-icon>
            新增标签
          </el-button>
          <div class="field-hint">
            「平台分类」是列表里显示的字符串，分类标签是结构化数据（按分类 ID 筛选用的是它）。
            两者不联动，改一个不会自动改另一个。
          </div>
        </div>
      </el-form-item>

      <div class="row">
        <el-form-item label="出版时间">
          <el-input v-model="form.publishTime" clearable placeholder="2022-08-01 00:00:00" />
        </el-form-item>
        <el-form-item label="语言">
          <el-input v-model="form.language" clearable placeholder="zh-wr" />
        </el-form-item>
      </div>
      <div class="field-hint hint-block">
        出版时间是字符串，筛选时按字典序比较。填 2022 表示「2022 全年」，填 2022-06
        表示「2022 年 6 月」，也可以填完整的 2022-06-01 00:00:00。
      </div>

      <div class="row row-3">
        <el-form-item label="是否出版">
          <el-select v-model="form.ispub" clearable placeholder="不修改">
            <el-option label="是" :value="1" />
            <el-option label="否" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="是否完结">
          <el-select v-model="form.finished" clearable placeholder="不修改">
            <el-option label="是" :value="1" />
            <el-option label="否" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="是否免费">
          <el-select v-model="form.free" clearable placeholder="不修改">
            <el-option label="是" :value="1" />
            <el-option label="否" :value="0" />
          </el-select>
        </el-form-item>
      </div>

      <div class="row">
        <el-form-item label="价格（元）">
          <el-input-number v-model="form.price" :min="0" :controls="false" placeholder="不修改" />
        </el-form-item>
        <el-form-item label="原价（元）">
          <el-input-number
            v-model="form.originalPrice"
            :min="0"
            :controls="false"
            placeholder="不修改"
          />
        </el-form-item>
      </div>
      <div class="field-hint hint-block">
        数字和下拉框留空 = 不修改。后端用 null 表示「这个字段不动」，
        而 0 是有意义的真实值（价格 0 元 ≠ 价格未知），不能拿来当「空」。
      </div>

      <el-form-item label="简介">
        <el-input
          v-model="form.intro"
          type="textarea"
          :rows="6"
          resize="vertical"
          placeholder="简介"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="footer">
        <span class="changed">
          <template v-if="changedLabels.length">
            将提交 {{ changedLabels.length }} 个字段：{{ changedLabels.join('、') }}
          </template>
          <template v-else>尚未修改任何字段</template>
        </span>
        <div class="footer-actions">
          <el-button @click="handleClose">取消</el-button>
          <el-button
            type="primary"
            :loading="saving"
            :disabled="changedLabels.length === 0"
            @click="submit"
          >
            保存
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.tip {
  margin-bottom: 16px;
}

.form :deep(.el-input-number) {
  width: 100%;
}

.row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 12px;
}
.row-3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.cat-list {
  width: 100%;
}
.cat-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.cat-num {
  width: 116px;
}
.cat-num-sm {
  width: 84px;
}
.cat-title {
  flex: 1;
  min-width: 0;
}

.field-hint {
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}
.hint-block {
  margin: -8px 0 16px 88px;
}

.footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.changed {
  font-size: 12px;
  color: #909399;
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.footer-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
</style>
