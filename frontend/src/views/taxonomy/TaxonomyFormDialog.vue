<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createTaxonomy, updateTaxonomy } from '@/api/taxonomy'
import type { TargetType, TaxonomyItem, TaxonomyRequest } from '@/types'

/**
 * 分类 / 榜单字典项的新建与编辑。
 *
 * <h3>为什么 `code` 在编辑时是只读的</h3>
 * 后端 `TaxonomyService.update()` 里有一条硬校验：请求体带了 `code`
 * **且与原值不同** → 直接报错，而不是静默忽略。
 *
 * 为什么不静默忽略？因为 `code` 是采集目标的身份：任务的 `targetId`、
 * 游标的主键 `(targetType, targetId)`、审计记录的 `targetId` 全都指向它。
 * 悄悄改掉会让「任务还在跑，但它采的东西换了」—— 而用户以为自己只是改了个名字。
 *
 * 所以前端也不给改：把它渲染成只读文本 + 一句说明，比让用户改完再被拒绝好。
 *
 * <h3>编辑时为什么不能只提交改过的字段</h3>
 * 与图书编辑不同，这里的后端是「**传了非 null 才覆盖**」的语义
 * （`if (name != null) item.setName(name)`），所以只提交改过的字段是安全的、
 * 也是更正确的做法 —— 少写几个字段就少一次意外覆盖的机会。
 */

const props = defineProps<{
  modelValue: boolean
  mode: 'create' | 'edit'
  type: TargetType
  item: TaxonomyItem | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved'): void
}>()

const formRef = ref<FormInstance>()
const saving = ref(false)

const form = reactive<{
  code: string
  name: string
  enabled: boolean
  sort?: number
  remark: string
}>({
  code: '',
  name: '',
  enabled: true,
  sort: undefined,
  remark: '',
})

/** 编辑时的原始值快照，用来只提交改过的字段 */
let snapshot = { name: '', enabled: true, sort: undefined as number | undefined, remark: '' }

const typeLabel = computed(() => (props.type === 'RANKING' ? '榜单' : '分类'))

const rules: FormRules = {
  code: [{ required: true, message: 'code 不能为空', trigger: 'blur' }],
  name: [{ required: true, message: '名称不能为空', trigger: 'blur' }],
}

function resetForm(): void {
  form.code = ''
  form.name = ''
  form.enabled = true
  form.sort = undefined
  form.remark = ''
  snapshot = { name: '', enabled: true, sort: undefined, remark: '' }
}

function fillFrom(item: TaxonomyItem | null): void {
  if (!item) {
    resetForm()
    return
  }
  form.code = item.code
  form.name = item.name
  form.enabled = item.enabled !== false
  form.sort = item.sort ?? undefined
  form.remark = item.remark ?? ''
  snapshot = {
    name: form.name,
    enabled: form.enabled,
    sort: form.sort,
    remark: form.remark,
  }
}

watch(
  () => [props.modelValue, props.mode, props.item?.id] as const,
  ([visible]) => {
    if (visible) {
      fillFrom(props.mode === 'edit' ? props.item : null)
      formRef.value?.clearValidate()
    }
  },
  { immediate: true },
)

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }

  let payload: TaxonomyRequest
  if (props.mode === 'edit') {
    payload = {}
    if (form.name !== snapshot.name) {
      payload.name = form.name
    }
    if (form.enabled !== snapshot.enabled) {
      payload.enabled = form.enabled
    }
    if (form.sort !== snapshot.sort) {
      payload.sort = form.sort
    }
    if (form.remark !== snapshot.remark) {
      payload.remark = form.remark
    }
    if (Object.keys(payload).length === 0) {
      ElMessage.warning('没有需要保存的修改')
      return
    }
  } else {
    payload = {
      code: form.code.trim(),
      name: form.name.trim(),
      enabled: form.enabled,
      sort: form.sort,
      remark: form.remark,
    }
  }

  saving.value = true
  try {
    if (props.mode === 'edit' && props.item?.id) {
      await updateTaxonomy(props.type, props.item.id, payload)
      ElMessage.success('已保存')
    } else {
      await createTaxonomy(props.type, payload)
      ElMessage.success('已新增')
    }
    emit('saved')
    emit('update:modelValue', false)
  } catch {
    // 拦截器已经弹过后端的 message（重复 code、改 code 都会被拒）
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
    :title="mode === 'create' ? `新增${typeLabel}` : `编辑${typeLabel}`"
    width="520px"
    top="10vh"
    destroy-on-close
    @update:model-value="handleClose"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="code" prop="code">
        <template v-if="mode === 'create'">
          <el-input v-model="form.code" clearable :placeholder="type === 'RANKING' ? '如 rising' : '如 300000'" />
        </template>
        <template v-else>
          <el-input :model-value="form.code" disabled />
        </template>
      </el-form-item>
      <div class="hint">
        code 是采集目标的身份：任务的 targetId、游标的主键、审计记录都指向它。
        <template v-if="mode === 'edit'">所以编辑时不允许修改 —— 要换目标请新建一条。</template>
        <template v-else>分类用数字串（如 300000），榜单用英文串（如 rising）。</template>
      </div>

      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" clearable placeholder="如 文学 / 飙升榜" />
      </el-form-item>

      <el-form-item label="启用">
        <el-switch v-model="form.enabled" />
      </el-form-item>
      <div class="hint">停用后：新建任务的下拉框里不会再出现它（已有任务照常跑）。</div>

      <el-form-item label="排序">
        <el-input-number v-model="form.sort" :controls="false" placeholder="越小越靠前" />
      </el-form-item>

      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="3" resize="vertical" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">
        {{ mode === 'create' ? '新增' : '保存' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: -12px 0 16px 80px;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}
:deep(.el-input-number) {
  width: 100%;
}
</style>
