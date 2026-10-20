<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Component } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Check, Heart, Loader2, Plus, Shield, Tag, Target, Wallet } from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import Button from '@/components/ui/Button.vue'
import PageFooter from '@/components/PageFooter.vue'
import { useRequest } from '@/composables/useApi'
import {
  createPlanType,
  createSavingsPlan,
  deletePlanType,
  getNewPlanCandidates,
  getPlanTypes,
  getSavingsPlans,
  renamePlanType,
} from '@/api'
import { formatCNY, targetDateFromMonthly } from '@/lib/finance'
import type { SavingsPlanPayload, SavingsPlanTypeItem } from '@/api/types'

/**
 * 新建存钱计划（路由 /savings/new）。
 *
 * 原来列表页的两个入口都指向 /savings/new，但那条路径命中的是详情页、后端按「不存在」返回 404，
 * 所以点「新建计划」只会看到「未找到该存钱计划」—— 这个页面就是把那条链路补上。
 *
 * **类型可以自定义**（2026-10-13 起）：卡片列表来自 /savings/plan-types（内置三种由后端幂等补种），
 * 「自定义类型」放在旁边，用户填个名字就能建；「管理类型」里能改名 / 删除。
 * 改名只改显示名（计划里存的是 code），删除时后端会挡「还有计划在用」。
 *
 * 两条与后端一致的规则（表单先拦一道，服务端也在 SavingsService.create 里拦）：
 * - 「总资产」计划全账号只能有一条：列表页只把第一条 type=asset 渲染成横跨整行的大卡，
 *   多出来的在页面上根本看不见，所以已有总资产计划时这里直接禁用该选项；
 * - 目标日期不能早于开始日期。
 */

const router = useRouter()

// 需要知道「是否已有总资产计划」才能决定类型选项能不能点；失败也不拦着填表，默认按没有处理
const { data: existingPlans } = useRequest(() => getSavingsPlans())
const hasAssetPlan = computed(() => (existingPlans.value ?? []).some((p) => p.type === 'asset'))

/** 类型列表（内置 + 自定义）：卡片、管理面板都用它 */
const { data: planTypes, reload: reloadTypes } = useRequest(() => getPlanTypes())

/** 内置三种类型各自的图标与说明；自定义类型用通用图标 + 「自定义类型」说明 */
const TYPE_ICONS: Record<string, Component> = { asset: Wallet, life: Shield, wish: Heart }
const TYPE_HINTS: Record<string, string> = {
  asset: '全账号只能有一个，横跨列表整行',
  life: '应急金、备用金这类',
  wish: '一次旅行、一件想买的东西…',
}

const typeOptions = computed(() =>
  (planTypes.value ?? []).map((type) => ({
    value: type.code,
    label: type.name,
    hint: TYPE_HINTS[type.code] ?? '自定义类型',
    icon: TYPE_ICONS[type.code] ?? Tag,
  })),
)

/* ==================== 自定义类型：新建 / 改名 / 删除 ==================== */

const addingType = ref(false)
const newTypeName = ref('')
const managingTypes = ref(false)
const editingTypeId = ref<string | null>(null)
const editingTypeName = ref('')
const typeSaving = ref(false)
const typeError = ref('')

async function addType() {
  const name = newTypeName.value.trim()
  if (!name) {
    typeError.value = '请输入类型名称'
    return
  }
  typeSaving.value = true
  typeError.value = ''
  try {
    const created = await createPlanType(name)
    await reloadTypes()
    form.value.type = created.code // 建完直接选中，省一次点击
    addingType.value = false
    newTypeName.value = ''
  } catch (e) {
    typeError.value = e instanceof Error ? e.message : String(e)
  } finally {
    typeSaving.value = false
  }
}

function startEditType(type: SavingsPlanTypeItem) {
  editingTypeId.value = type.id
  editingTypeName.value = type.name
  typeError.value = ''
}

async function saveTypeName(type: SavingsPlanTypeItem) {
  const name = editingTypeName.value.trim()
  if (!name) {
    typeError.value = '请输入类型名称'
    return
  }
  typeSaving.value = true
  typeError.value = ''
  try {
    await renamePlanType(type.id, name)
    await reloadTypes()
    editingTypeId.value = null
  } catch (e) {
    typeError.value = e instanceof Error ? e.message : String(e)
  } finally {
    typeSaving.value = false
  }
}

async function removeType(type: SavingsPlanTypeItem) {
  typeSaving.value = true
  typeError.value = ''
  try {
    await deletePlanType(type.id)
    await reloadTypes()
    // 删的正好是当前选中的类型 → 退回第一个可用的
    if (form.value.type === type.code) {
      form.value.type = planTypes.value?.[0]?.code ?? 'wish'
    }
  } catch (e) {
    // 后端会挡「还有计划在用 / 系统类型」，把原话显示出来
    typeError.value = e instanceof Error ? e.message : String(e)
  } finally {
    typeSaving.value = false
  }
}

/** 今天 YYYY-MM-DD（开始日期默认值） */
function today(): string {
  return new Date().toISOString().slice(0, 10)
}

const form = ref({
  name: '',
  type: 'wish',
  targetAmount: '',
  monthlyPlanAmount: '',
  startDate: today(),
  targetDate: '',
  description: '',
  image: '',
  note: '',
})

// 类型列表回来后兜一道：默认的 wish 可能被用户删掉了，退回列表里第一个可用类型
watch(planTypes, (list) => {
  if (!list?.length) return
  if (!list.some((type) => type.code === form.value.type)) {
    form.value.type = list[0].code
  }
})

/**
 * 是否设定目标金额（2026-10-05 加）。关掉 = 这个计划不追目标，只看选中的这组资产的总体收益率，
 * 目标金额 / 目标日期 / 每月计划存入 三个框一起隐藏、提交时也不带。
 * 「总资产」计划必须带目标（后端也会拒），所以选它时这个开关强制打开并禁用。
 */
const hasTarget = ref(true)

/** 表单里勾选的资产（只有生活保障 / 愿望用得上；总资产计划本来就全含） */
const selectedAssetIds = ref<string[]>([])

/**
 * 「目标日期」留空时的实时预览（口径见 lib/finance.targetDateFromMonthly，与后端一致）。
 * 只用于提示；真正落库的日期由后端算。
 */
const autoTargetDate = computed(() =>
  targetDateFromMonthly(
    form.value.startDate,
    Number(form.value.targetAmount),
    Number(form.value.monthlyPlanAmount),
  ),
)

// 新建时还没有计划，用「计划无关」的候选接口
const { data: candidates, loading: candidatesLoading } = useRequest(() => getNewPlanCandidates())

function toggleAsset(id: string) {
  const index = selectedAssetIds.value.indexOf(id)
  if (index >= 0) selectedAssetIds.value.splice(index, 1)
  else selectedAssetIds.value.push(id)
}

const saving = ref(false)
const error = ref('')

/** 与 AdjustmentForm / EntryDialog 同一个字符串（项目里没有 Input 组件，各处照抄一份） */
const inputClass =
  'w-full rounded-lg border border-border bg-background px-3.5 py-2.5 text-sm transition-colors placeholder:text-muted-foreground/60 focus:outline-none focus:ring-2 focus:ring-brand/30 focus:border-brand disabled:opacity-50 disabled:cursor-not-allowed'

function pickType(type: string) {
  // 总资产计划只能有一个；已经有的话这一项是禁用的（这里再兜一道）
  if (type === 'asset' && hasAssetPlan.value) return
  form.value.type = type
  // 总资产计划 = 「全部未归档资产」的目标，必须有目标金额；切到它时把开关顶上
  if (type === 'asset') hasTarget.value = true
}

async function submit() {
  error.value = ''
  const name = form.value.name.trim()
  const targetAmount = Number(form.value.targetAmount)
  const monthlyPlanAmount = Number(form.value.monthlyPlanAmount)

  // 手写校验：与后端 SavingsService.validateNewPlan 一一对应，先在后端前面拦一道，省一次往返
  if (!name) return (error.value = '请填写计划名称')
  if (!form.value.startDate) return (error.value = '请选择开始日期')
  if (form.value.type === 'asset' && hasAssetPlan.value) return (error.value = '已有总资产计划，只能新建生活保障或愿望')
  if (form.value.type === 'asset' && !hasTarget.value) return (error.value = '总资产计划需要设定目标金额')
  if (hasTarget.value) {
    if (!(targetAmount > 0)) return (error.value = '目标金额要大于 0')
    if (!(monthlyPlanAmount > 0)) return (error.value = '每月计划存入要大于 0')
    // 目标日期选填：留空由后端按每月存入自适应推算（这里的 autoTargetDate 只是预览）
    if (form.value.targetDate && form.value.targetDate < form.value.startDate) {
      return (error.value = '目标日期不能早于开始日期')
    }
  }

  const payload: SavingsPlanPayload = {
    name,
    type: form.value.type,
    startDate: form.value.startDate,
    // 不设目标时这三个字段整个不传：后端会落 NULL（也避免空串日期被 Jackson 判成 400）
    targetAmount: hasTarget.value ? targetAmount : undefined,
    monthlyPlanAmount: hasTarget.value ? monthlyPlanAmount : undefined,
    targetDate: hasTarget.value && form.value.targetDate ? form.value.targetDate : undefined,
    // 选填字段留空传 undefined：后端把空白串一律存 null，但少传一个字段更干净
    description: form.value.description.trim() || undefined,
    image: form.value.image.trim() || undefined,
    note: form.value.note.trim() || undefined,
    // 勾了资产才带（总资产计划忽略该字段）
    assetIds:
      form.value.type !== 'asset' && selectedAssetIds.value.length
        ? [...selectedAssetIds.value]
        : undefined,
  }

  saving.value = true
  try {
    const created = await createSavingsPlan(payload)
    // replace 而不是 push：返回键不该回到一张已经提交过的空表单
    router.replace({ name: 'savings-detail', params: { id: created.id } })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <!-- min-h-screen + flex-col：脚注永远落在界面底部 -->
  <div
    class="mx-auto flex min-h-screen w-full max-w-[1400px] flex-col px-4 pt-6 pb-[18px] sm:px-10 lg:pt-4"
  >
    <!-- 占位：与投资看板的返回按钮区对齐 -->
    <div class="mb-4 flex shrink-0 items-center gap-3 lg:mb-3">
      <Button variant="ghost" size="sm" class="gap-1.5" @click="router.push({ name: 'savings' })">
        <ArrowLeft class="size-4" />
        返回
      </Button>
    </div>

    <!-- 标题区 -->
    <div class="mb-6 space-y-1.5">
      <h1 class="text-3xl font-bold tracking-tight">新建存钱计划</h1>
      <p class="text-sm text-muted-foreground">把钱和目标联系起来，让每一份努力都有意义。</p>
    </div>

    <!-- 表单卡片 -->
    <Card class="mx-auto w-full max-w-2xl">
      <div class="flex items-start gap-4 px-5 pt-5 sm:px-6 sm:pt-6">
        <div
          class="flex size-11 shrink-0 items-center justify-center rounded-xl bg-brand/10 text-brand"
        >
          <Plus class="size-5" />
        </div>
        <div class="space-y-1">
          <h2 class="text-lg font-semibold leading-tight">计划信息</h2>
          <p class="text-xs text-muted-foreground">
            带 * 的是必填项；新建计划的已存金额从 0 开始，进度跟着关联资产的市值走。
          </p>
        </div>
      </div>

      <form class="space-y-5 px-5 pb-6 pt-5 sm:px-6" @submit.prevent="submit">
        <!-- 计划名称 -->
        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground" for="plan-name">计划名称 *</label>
          <input
            id="plan-name"
            v-model="form.name"
            type="text"
            maxlength="100"
            :disabled="saving"
            :class="inputClass"
            placeholder="例如：日本旅行 / 30岁资产目标"
          />
        </div>

        <!-- 类型 -->
        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground">类型 *</label>
          <div class="grid gap-3 sm:grid-cols-2">
            <button
              v-for="opt in typeOptions"
              :key="opt.value"
              type="button"
              :disabled="saving || (opt.value === 'asset' && hasAssetPlan)"
              class="flex items-center gap-3 rounded-xl border p-3.5 text-left transition-all disabled:opacity-50 disabled:cursor-not-allowed"
              :class="
                form.type === opt.value
                  ? 'border-brand bg-brand/10 text-brand ring-1 ring-brand/30'
                  : 'border-border hover:bg-muted hover:border-foreground/20'
              "
              @click="pickType(opt.value)"
            >
              <component :is="opt.icon" class="size-5 shrink-0" />
              <div class="min-w-0">
                <p class="text-sm font-semibold leading-tight">{{ opt.label }}</p>
                <p class="mt-0.5 text-xs leading-tight text-muted-foreground">{{ opt.hint }}</p>
              </div>
            </button>
          </div>
          <p v-if="hasAssetPlan" class="text-xs text-muted-foreground">
            已有总资产计划（一个账号只能有一个），新计划只能建其他类型。
          </p>

          <!-- 自定义类型：一行按钮 + 管理面板（改名 / 删除） -->
          <div class="flex flex-wrap items-center gap-x-3 gap-y-2 pt-1">
            <template v-if="!addingType">
              <button
                type="button"
                class="inline-flex items-center gap-1.5 rounded-lg border border-dashed border-border px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-foreground/30 hover:text-foreground"
                @click="addingType = true"
              >
                <Plus class="size-3.5" />
                自定义类型
              </button>
            </template>
            <template v-else>
              <input
                v-model="newTypeName"
                type="text"
                placeholder="如 旅行基金"
                maxlength="32"
                class="h-8 w-40 rounded-lg border border-border bg-background px-2.5 text-sm outline-none focus-visible:ring-2 focus-visible:ring-brand/30 focus-visible:border-brand"
                @keyup.enter="addType"
                @keyup.esc="addingType = false"
              />
              <Button size="sm" :disabled="typeSaving" @click="addType">添加</Button>
              <Button variant="ghost" size="sm" @click="addingType = false">取消</Button>
            </template>

            <button
              type="button"
              class="ml-auto text-xs text-muted-foreground transition-colors hover:text-foreground"
              @click="managingTypes = !managingTypes"
            >
              {{ managingTypes ? '收起管理' : '管理类型' }}
            </button>
          </div>

          <ul v-if="managingTypes" class="divide-y divide-border rounded-xl border border-border">
            <li v-for="type in planTypes ?? []" :key="type.id" class="flex items-center gap-2 px-3 py-2">
              <span class="min-w-0 flex-1 truncate text-sm">{{ type.name }}</span>
              <span v-if="type.system" class="shrink-0 text-xs text-muted-foreground">系统类型</span>
              <template v-else-if="editingTypeId === type.id">
                <input
                  v-model="editingTypeName"
                  type="text"
                  maxlength="32"
                  class="h-7 w-36 rounded-lg border border-border bg-background px-2 text-sm outline-none focus-visible:ring-2 focus-visible:ring-brand/30 focus-visible:border-brand"
                  @keyup.enter="saveTypeName(type)"
                  @keyup.esc="editingTypeId = null"
                />
                <Button size="xs" :disabled="typeSaving" @click="saveTypeName(type)">保存</Button>
                <Button variant="ghost" size="xs" @click="editingTypeId = null">取消</Button>
              </template>
              <template v-else>
                <button
                  type="button"
                  class="shrink-0 text-xs text-muted-foreground transition-colors hover:text-foreground"
                  @click="startEditType(type)"
                >
                  改名
                </button>
                <button
                  type="button"
                  class="shrink-0 text-xs text-destructive transition-colors hover:underline"
                  :disabled="typeSaving"
                  @click="removeType(type)"
                >
                  删除
                </button>
              </template>
            </li>
          </ul>
          <p v-if="typeError" class="text-xs text-destructive">{{ typeError }}</p>
        </div>

        <!-- 设定目标开关：关掉就是「只看这组资产的收益」，目标金额/日期/每月存入一起消失 -->
        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground">目标</label>
          <button
            type="button"
            :disabled="saving || form.type === 'asset'"
            class="flex w-full items-center gap-3 rounded-xl border p-3.5 text-left transition-all disabled:cursor-not-allowed disabled:opacity-60"
            :class="hasTarget ? 'border-brand bg-brand/10 text-brand ring-1 ring-brand/30' : 'border-border hover:bg-muted hover:border-foreground/20'"
            @click="hasTarget = !hasTarget"
          >
            <span
              class="flex size-5 shrink-0 items-center justify-center rounded-md border"
              :class="hasTarget ? 'border-brand bg-brand text-brand-foreground' : 'border-border text-transparent'"
            >
              <Check class="size-3.5" />
            </span>
            <span class="min-w-0 flex-1">
              <span class="flex items-center gap-2 text-sm font-semibold leading-tight">
                <Target class="size-4" />
                设定目标金额
              </span>
              <span class="mt-0.5 block text-xs leading-tight text-muted-foreground">
                {{ form.type === 'asset'
                  ? '总资产计划必须设定目标金额'
                  : hasTarget
                    ? '按目标金额追进度'
                    : '不设目标：只看这组资产的整体收益率' }}
              </span>
            </span>
          </button>
        </div>

        <!-- 金额（只在设了目标时出现） -->
        <div v-if="hasTarget" class="grid gap-5 sm:grid-cols-2">
          <div class="space-y-2">
            <label class="text-sm font-medium text-foreground" for="plan-target">目标金额 *</label>
            <div class="relative">
              <span class="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">¥</span>
              <input
                id="plan-target"
                v-model="form.targetAmount"
                type="number"
                inputmode="decimal"
                step="0.01"
                min="0"
                :disabled="saving"
                :class="[inputClass, 'pl-8']"
                placeholder="10000"
              />
            </div>
          </div>
          <div class="space-y-2">
            <label class="text-sm font-medium text-foreground" for="plan-monthly">每月计划存入 *</label>
            <div class="relative">
              <span class="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">¥</span>
              <input
                id="plan-monthly"
                v-model="form.monthlyPlanAmount"
                type="number"
                inputmode="decimal"
                step="0.01"
                min="0"
                :disabled="saving"
                :class="[inputClass, 'pl-8']"
                placeholder="1000"
              />
            </div>
            <p class="text-xs text-muted-foreground">用于推算预计完成时间，要大于 0</p>
          </div>
        </div>

        <!-- 日期（目标日期只在设了目标时出现） -->
        <div class="grid gap-5 sm:grid-cols-2">
          <div class="space-y-2">
            <label class="text-sm font-medium text-foreground" for="plan-start">开始日期 *</label>
            <input
              id="plan-start"
              v-model="form.startDate"
              type="date"
              :disabled="saving"
              :class="inputClass"
            />
          </div>
          <div v-if="hasTarget" class="space-y-2">
            <label class="text-sm font-medium text-foreground" for="plan-target-date">目标日期（选填）</label>
            <input
              id="plan-target-date"
              v-model="form.targetDate"
              type="date"
              :disabled="saving"
              :class="inputClass"
            />
            <!-- 留空时的实时预览：口径与后端 estimatedTargetDate 一致，真正落库的以后端算的为准 -->
            <p class="text-xs text-muted-foreground">
              <template v-if="form.targetDate">留空就会按每月存入自动推算，填了就按你选的日期。</template>
              <template v-else-if="autoTargetDate">
                按每月 {{ formatCNY(Number(form.monthlyPlanAmount) || 0) }} 推算，预计 {{ autoTargetDate }} 达成。
              </template>
              <template v-else>留空就会按每月存入和开始日期自动推算。</template>
            </p>
          </div>
        </div>

        <!-- 描述 -->
        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground" for="plan-desc">描述</label>
          <input
            id="plan-desc"
            v-model="form.description"
            type="text"
            maxlength="512"
            :disabled="saving"
            :class="inputClass"
            placeholder="一句话说明这个计划（选填）"
          />
        </div>

        <!-- 封面图 -->
        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground" for="plan-image">封面图地址</label>
          <input
            id="plan-image"
            v-model="form.image"
            type="url"
            maxlength="255"
            :disabled="saving"
            :class="inputClass"
            placeholder="https://…（选填，留空用占位图）"
          />
        </div>

        <!-- 备注 -->
        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground" for="plan-note">备注</label>
          <textarea
            id="plan-note"
            v-model="form.note"
            rows="3"
            :disabled="saving"
            :class="[inputClass, 'resize-y']"
            placeholder="想对自己说的话、计划的细节（选填）"
          />
        </div>

        <!-- 选择资产：建计划时就把要计入的资产勾好，省得建完再进详情页挂 -->
        <div class="space-y-2">
          <div class="flex items-baseline justify-between gap-3">
            <label class="text-sm font-medium text-foreground">
              包含的资产
              <span v-if="form.type !== 'asset'" class="ml-1 text-xs font-normal text-muted-foreground">
                已选 {{ selectedAssetIds.length }} 项（可留空，之后再挂）
              </span>
            </label>
          </div>

          <p
            v-if="form.type === 'asset'"
            class="rounded-xl border border-border bg-muted/20 px-4 py-3 text-xs text-muted-foreground"
          >
            总资产计划默认包含全部未归档资产，不需要手动勾选。
          </p>
          <div v-else class="rounded-xl border border-border bg-muted/20 p-3">
            <p v-if="candidatesLoading" class="px-1 py-2 text-sm text-muted-foreground">加载中…</p>
            <p v-else-if="!candidates?.length" class="px-1 py-2 text-sm text-muted-foreground">
              名下还没有资产，先到投资看板点「添加资产」记一笔。
            </p>
            <ul v-else class="max-h-[260px] space-y-1 overflow-y-auto">
              <li v-for="c in candidates" :key="c.id">
                <button
                  type="button"
                  :disabled="saving || c.archived || !!c.linkedPlanName"
                  class="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left transition-colors hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
                  :title="
                    c.archived
                      ? '已归档的资产不计入计划，先取消归档再关联'
                      : c.linkedPlanName
                        ? `已在「${c.linkedPlanName}」里，一个资产只能属于一个分类`
                        : undefined
                  "
                  @click="toggleAsset(c.id)"
                >
                  <span
                    class="flex size-5 shrink-0 items-center justify-center rounded-md border text-xs"
                    :class="selectedAssetIds.includes(c.id)
                      ? 'border-brand bg-brand text-brand-foreground'
                      : 'border-border text-transparent'"
                  >
                    <Check class="size-3.5" />
                  </span>
                  <span class="min-w-0 flex-1 truncate text-sm">
                    {{ c.name }}
                    <span class="ml-1 font-mono text-xs text-muted-foreground">{{ c.code }}</span>
                  </span>
                  <span v-if="c.archived" class="shrink-0 text-xs text-muted-foreground">已归档</span>
                  <span v-else-if="c.linkedPlanName" class="shrink-0 text-xs text-muted-foreground">
                    已在「{{ c.linkedPlanName }}」
                  </span>
                  <span class="shrink-0 font-mono text-sm tabular-nums text-muted-foreground">
                    {{ formatCNY(c.amount) }}
                  </span>
                </button>
              </li>
            </ul>
          </div>
        </div>

        <p v-if="error" class="text-sm text-destructive">{{ error }}</p>

        <div class="flex items-center gap-3 pt-1">
          <Button type="submit" :disabled="saving" class="gap-2">
            <Loader2 v-if="saving" class="size-4 animate-spin" />
            <Plus v-else class="size-4" />
            {{ saving ? '创建中…' : '创建计划' }}
          </Button>
          <Button
            type="button"
            variant="ghost"
            :disabled="saving"
            @click="router.push({ name: 'savings' })"
          >
            取消
          </Button>
        </div>
      </form>
    </Card>

    <PageFooter />
  </div>
</template>
