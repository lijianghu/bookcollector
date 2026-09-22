<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createTask, updateTask } from '@/api/task'
import { listTaxonomy } from '@/api/taxonomy'
import type { CollectTask, TargetType, TaxonomyItem } from '@/types'

/**
 * 新建 / 编辑采集任务（一个弹窗两种模式）。
 *
 * <h3>为什么合并成一个弹窗</h3>
 * 两种模式有 3 个字段完全一样（`name` / `maxPages` / `remark`），
 * 拆成两个组件就得把那 3 个 form-item 抄两遍，以后改校验规则要改两处。
 * 差别只有一处：**新建要选目标，编辑不能改目标**。
 *
 * <h3>编辑为什么不能改目标</h3>
 * 后端 `TaskService.update()` 的签名里**根本没有** `targetType` / `targetId`。
 * 原因是它们是游标的归属键 —— `collect_cursors` 的主键就是 `(targetType, targetId)`。
 * 改了目标等于换了一本账，而任务名和历史运行记录还指着旧目标。
 * 所以编辑模式里把目标**只读展示**出来，并写明「想换目标请删了重建」。
 *
 * <h3>新建时为什么要把「已有任务的目标」从下拉里剔掉</h3>
 * `collect_tasks` 上有 `(targetType, targetId)` 唯一索引，重复新建必定
 * 返回 code=405「目标已经有一个任务了」。让用户在一个必然失败的选项上
 * 浪费时间是很差的设计 —— 所以直接不列出来，并解释为什么。
 * 万一有并发（用户 A 建完、用户 B 的下拉还是旧的），后端那道 405 仍然兜底。
 */

const props = defineProps<{
  modelValue: boolean
  mode: 'create' | 'edit'
  /** 编辑模式下的任务 */
  task: CollectTask | null
  /** 已有任务列表。用来剔除「已经有任务的目标」 */
  existingTasks: CollectTask[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved', task: CollectTask): void
}>()

const formRef = ref<FormInstance>()
const saving = ref(false)

const form = reactive<{
  name: string
  targetType: TargetType
  targetId: string
  maxPages?: number
  remark: string
}>({
  name: '',
  targetType: 'CATEGORY',
  targetId: '',
  maxPages: undefined,
  remark: '',
})

const rules: FormRules = {
  targetType: [{ required: true, message: '请选择目标类型', trigger: 'change' }],
  targetId: [{ required: true, message: '请选择采集目标', trigger: 'change' }],
}

/** 字典。新建时才需要，编辑模式不加载 */
const categories = ref<TaxonomyItem[]>([])
const rankings = ref<TaxonomyItem[]>([])

const dictOfCurrentType = computed(() =>
  form.targetType === 'RANKING' ? rankings.value : categories.value,
)

/** 已经被别的任务占用的目标 key，形如 `CATEGORY:300000` */
const usedTargetKeys = computed<Set<string>>(() => {
  const set = new Set<string>()
  props.existingTasks.forEach((t) => {
    if (t.targetType && t.targetId) {
      set.add(`${t.targetType}:${t.targetId}`)
    }
  })
  return set
})

/** 可选目标 = 启用的字典项 − 已有任务的目标 */
const availableTargets = computed(() => {
  return dictOfCurrentType.value
    .filter((t) => !usedTargetKeys.value.has(`${form.targetType}:${t.code}`))
    .map((t) => ({ value: t.code, label: `${t.name}（${t.code}）` }))
})

/** 有多少个目标因为「已经有任务」被剔掉了 —— 用来解释下拉为什么短 */
const hiddenCount = computed(
  () => dictOfCurrentType.value.length - availableTargets.value.length,
)

async function loadDict(): Promise<void> {
  try {
    // enabledOnly=true：停用的字典项不该再新建任务
    const [cats, ranks] = await Promise.all([
      listTaxonomy('CATEGORY', true),
      listTaxonomy('RANKING', true),
    ])
    categories.value = cats
    rankings.value = ranks
  } catch {
    categories.value = []
    rankings.value = []
  }
}

function resetForm(): void {
  form.name = ''
  form.targetType = 'CATEGORY'
  form.targetId = ''
  form.maxPages = undefined
  form.remark = ''
}

function fillFrom(task: CollectTask | null): void {
  if (!task) {
    resetForm()
    return
  }
  form.name = task.name ?? ''
  form.targetType = task.targetType
  form.targetId = task.targetId
  form.maxPages = task.maxPages ?? undefined
  form.remark = task.remark ?? ''
}

/**
 * 切换目标类型时清空已选目标。
 *
 * 不清的话会出现：先选「分类 → 300000」，再切成「榜单」，
 * 目标还留着 "300000"，提交后后端拿 `ranking:300000` 建任务 ——
 * 建出来的任务永远采不到东西，而且不报错。
 */
function handleTargetTypeChange(): void {
  form.targetId = ''
}

watch(
  () => [props.modelValue, props.mode, props.task?.id] as const,
  ([visible]) => {
    if (!visible) {
      return
    }
    fillFrom(props.mode === 'edit' ? props.task : null)
    formRef.value?.clearValidate()
    if (props.mode === 'create') {
      void loadDict()
    }
  },
  { immediate: true },
)

/** 编辑模式下的目标展示文案（只读） */
const targetText = computed(() => {
  if (!props.task) {
    return '-'
  }
  const typeName = props.task.targetType === 'RANKING' ? '榜单' : '分类'
  return `${typeName} · ${props.task.targetName ?? props.task.targetId}（${props.task.targetId}）`
})

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  try {
    if (props.mode === 'edit') {
      if (!props.task?.id) {
        return
      }
      // ⚠️ 只提交 name / maxPages / remark —— 后端也不接受别的字段
      const updated = await updateTask(props.task.id, {
        name: form.name,
        maxPages: form.maxPages,
        remark: form.remark,
      })
      ElMessage.success('任务已保存')
      emit('saved', updated)
    } else {
      const target = dictOfCurrentType.value.find((t) => t.code === form.targetId)
      const created = await createTask({
        name: form.name,
        targetType: form.targetType,
        targetId: form.targetId,
        // 目标名快照：任务建完之后，即使字典项被删/改名，任务里也还知道自己在采什么
        targetName: target?.name,
        maxPages: form.maxPages,
        remark: form.remark,
      })
      ElMessage.success('任务已创建')
      emit('saved', created)
    }
    emit('update:modelValue', false)
  } catch {
    // 拦截器已经弹过后端的 message（405「目标已经有一个任务了」也走这里）
  } finally {
    saving.value = false
  }
}

function handleClose(): void {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="mode === 'create' ? '新建采集任务' : '编辑任务'"
    width="560px"
    top="8vh"
    destroy-on-close
    @update:model-value="handleClose"
  >
    <el-alert v-if="mode === 'create'" type="info" :closable="false" class="tip">
      <template #title>
        一个采集目标只能有一个任务（数据库上有唯一索引）。已经建过任务的目标不会出现在
        下面的下拉框里 —— 想改参数就编辑那个任务，不要建第二个。
      </template>
    </el-alert>
    <el-alert v-else type="warning" :closable="false" class="tip">
      <template #title>
        目标不可修改。它决定采集游标归谁（游标按「目标」存），改了等于换一本账，
        而历史运行记录还指着旧目标。想换目标请删掉这个任务重建。
      </template>
    </el-alert>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="88px" class="form">
      <el-form-item label="任务名">
        <el-input
          v-model="form.name"
          clearable
          placeholder="留空自动生成，如「文学-采集」"
        />
      </el-form-item>

      <!-- 新建：选目标 -->
      <template v-if="mode === 'create'">
        <el-form-item label="目标类型" prop="targetType">
          <el-radio-group v-model="form.targetType" @change="handleTargetTypeChange">
            <el-radio-button value="CATEGORY">分类</el-radio-button>
            <el-radio-button value="RANKING">榜单</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="采集目标" prop="targetId">
          <el-select v-model="form.targetId" clearable filterable placeholder="选择要采集的分类或榜单">
            <el-option
              v-for="opt in availableTargets"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <div class="field-hint">
          可选 {{ availableTargets.length }} 个
          <template v-if="hiddenCount > 0">
            （另有 {{ hiddenCount }} 个已建过任务，不在此列出）
          </template>
        </div>
      </template>

      <!-- 编辑：目标只读 -->
      <el-form-item v-else label="采集目标">
        <span class="readonly-target">{{ targetText }}</span>
      </el-form-item>

      <el-form-item label="最大页数">
        <el-input-number
          v-model="form.maxPages"
          :min="0"
          :controls="false"
          placeholder="0 或不填 = 不限，一直翻到底"
        />
      </el-form-item>
      <div class="field-hint">
        留空或填 0 表示不限制，会一直翻到接口说没有下一页。想先试跑就填个 1~2。
      </div>

      <el-form-item label="备注">
        <el-input
          v-model="form.remark"
          type="textarea"
          :rows="3"
          resize="vertical"
          placeholder="可选"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">
        {{ mode === 'create' ? '创建' : '保存' }}
      </el-button>
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

.field-hint {
  margin: -8px 0 16px 88px;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}

.readonly-target {
  font-size: 13px;
  color: #606266;
}
</style>
