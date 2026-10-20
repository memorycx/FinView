<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { Focus } from '@lucide/vue'
import Button from '@/components/ui/Button.vue'
import type { PanoramaBucket } from '@/api/types'
import { panoramaColor, scenePalette } from '@/lib/panorama'
import { isDark } from '@/composables/useTheme'
import { formatWan } from '@/lib/finance'

/**
 * 「资金全景」的 3D 场景（three.js）。
 *
 * 一座塔 = 一个展示桶，塔里每一截 = 一只资产（颜色按桶内轮转、与右侧折线同色）。
 * 塔高按「当前市值」定标；`evolveMonth` 从 0 走到 12 时，每截按该资产的**未来累计投入**
 * 长高（只算投入、不含涨跌），帧循环里用 lerp 补间，所以是连续长起来而不是跳。
 *
 * 点塔 = 选中：其余塔淡出、选中的一座平移到画面左侧（右侧留出来给折线面板）；
 * 点空白处取消。标签是 HTML 覆盖层（每帧把塔顶投影到屏幕坐标），没画进 canvas。
 *
 * **相机可自由摆位**（OrbitControls）：左键拖拽转视角、右键（或 Shift+左键）平移、滚轮缩放；
 * 拖过之后左下角出现「复位视角」回到默认机位。选择塔的落位点按**当前**视线算
 * （见 `computeFocus`），所以转过视角再点塔，它照样停在画面左侧而不是跑到镜头背后；
 * 拖拽和点击共用左键，靠指针位移（`CLICK_SLOP`）区分。
 *
 * 几何要点：塔身用 BoxGeometry + 边缘亮线做"辉光描边"（参考设计稿的观感），
 * 不用后期 bloom；总高按「演变到最后一月」的最大值定标，动画全程画面不跳。
 */
const props = defineProps<{
  buckets: PanoramaBucket[]
  /** 演变进度：0 = 今天；1..12 = 演变到第 i 个月末 */
  evolveMonth: number
  /** 选中的桶 key（null = 全景） */
  selected: string | null
}>()

const emit = defineEmits<{
  select: [key: string | null]
}>()

/** 场景里最高的一座塔占多少单位高 */
const MAX_TOWER_H = 7.6
const SEG_SIZE = 1.5
const RING_RADIUS = 6.0

/** 默认机位（抬高 + 拉远：前排塔底不被画布下沿裁掉，前排塔身不挡后排），「复位视角」回到这里 */
const DEFAULT_CAMERA_POS = new THREE.Vector3(0, 11, 19)
const DEFAULT_TARGET = new THREE.Vector3(0, 3.0, -0.5)
/** 自由摆位的边界：最近能贴到塔身、最远把整组塔框进画面 */
const MIN_DISTANCE = 6
const MAX_DISTANCE = 45
/** 俯仰角：不许完全俯视（塔会缩成方块），也不许低到地面以下 */
const MIN_POLAR = 0.3
const MAX_POLAR = 1.52
/** 目标点离场景中心最远能拖出去多少（再远就是对着空画布转了） */
const MAX_TARGET_RADIUS = 14
/** 左键松手时指针位移超过这么多像素，就算「拖视角」而不是「点击选塔」 */
const CLICK_SLOP = 5

/** 当前主题下的一组基础色（建场景 / 切主题重建时各取一次） */
let palette = scenePalette()

const host = ref<HTMLDivElement | null>(null)
/** 标签（桶名 + 金额）的屏幕位置，每帧更新 */
const labels = ref<{ key: string; label: string; value: string; x: number; y: number }[]>([])
/** 视角是否已经离开默认机位（是 → 左下角出「复位视角」） */
const viewMoved = ref(false)

interface Segment {
  mesh: THREE.Mesh
  index: number
  assetValue: number
  cumulative: number[]
  color: string
  currentH: number
  targetH: number
}

interface Tower {
  key: string
  label: string
  group: THREE.Group
  segments: Segment[]
  baseValue: number
  /** 目标位置（选中时移到左侧） */
  home: THREE.Vector3
  focus: THREE.Vector3
}

/** 每「元」对应多少场景单位（见 computeUnit） */
let unit = 1

let renderer: THREE.WebGLRenderer | undefined
let scene: THREE.Scene | undefined
let camera: THREE.PerspectiveCamera | undefined
let controls: OrbitControls | undefined
let frame = 0
let resizeObserver: ResizeObserver | undefined
const towers = new Map<string, Tower>()
const pickables: THREE.Mesh[] = []
const disposables: (THREE.BufferGeometry | THREE.Material)[] = []
/** 帧循环里的临时向量（避免每帧 new） */
const tmpForward = new THREE.Vector3()
const tmpTop = new THREE.Vector3()
const tmpDelta = new THREE.Vector3()

/**
 * 选中塔的落位点：**按当前视线**算，停在画面左侧偏前（右半边留给折线面板）。
 *
 * 默认机位下解出来就是写死的 (-4.6, 0, 4.2)，和以前一致；视角被转/拉过之后就跟着新的
 * 视线方向走 —— 否则转过 180° 再点塔，它会被平移到镜头背后（点一下就没了）。
 * 正俯视（前方向在地面上没有分量）时退回默认点。
 */
function computeFocus(): THREE.Vector3 {
  const fallback = new THREE.Vector3(-4.6, 0, 4.2)
  if (!camera || !controls) return fallback
  const center = new THREE.Vector3(controls.target.x, 0, controls.target.z)
  const forward = new THREE.Vector3(
    controls.target.x - camera.position.x,
    0,
    controls.target.z - camera.position.z,
  )
  if (forward.lengthSq() < 1e-4) return fallback
  forward.normalize()
  // 镜头左方 = 世界上方 × 前方向（右手系下就是屏幕左侧）
  const left = new THREE.Vector3().crossVectors(new THREE.Vector3(0, 1, 0), forward).normalize()
  return center.addScaledVector(forward, -4.7).addScaledVector(left, 4.6)
}

/** 把桶摊成塔：n 座在半径 RING_RADIUS 的弧上排开（金额大的放中间偏前，与参考图一致） */
function placeTowers(buckets: PanoramaBucket[]) {
  const n = buckets.length
  return buckets.map((bucket, index) => {
    // n 座均匀分布在 [-70°, 70°] 的正面弧上；只有一座时放在正中
    const t = n === 1 ? 0 : (-70 + (140 * index) / (n - 1)) * (Math.PI / 180)
    return {
      bucket,
      home: new THREE.Vector3(RING_RADIUS * Math.sin(t), 0, RING_RADIUS * Math.cos(t)),
      focus: computeFocus(),
    }
  })
}

function buildScene(buckets: PanoramaBucket[]) {
  if (!scene) return
  // 清掉旧的塔（切数据/热更新时）
  for (const tower of towers.values()) {
    scene.remove(tower.group)
  }
  towers.clear()
  pickables.length = 0

  for (const { bucket, home, focus } of placeTowers(buckets)) {
    const group = new THREE.Group()
    group.position.copy(home)

    const segments: Segment[] = bucket.assets.map((asset, index) => {
      const color = panoramaColor(asset.colorVar)
      const geometry = new THREE.BoxGeometry(SEG_SIZE, 1, SEG_SIZE)
      const material = new THREE.MeshStandardMaterial({
        color,
        transparent: true,
        opacity: 0.92,
        metalness: 0.15,
        roughness: 0.4,
        emissive: new THREE.Color(color).multiplyScalar(0.32),
      })
      const mesh = new THREE.Mesh(geometry, material)
      const edgeGeometry = new THREE.EdgesGeometry(geometry)
      const edgeMaterial = new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.95 })
      // 描边是 mesh 的子节点，跟着父级的 scale 一起拉伸，天然贴合每一截
      mesh.add(new THREE.LineSegments(edgeGeometry, edgeMaterial))
      disposables.push(geometry, material, edgeGeometry, edgeMaterial)
      pickables.push(mesh)
      group.add(mesh)
      return {
        mesh,
        index,
        assetValue: asset.value,
        cumulative: asset.cumulative,
        color,
        currentH: 0.0001,
        targetH: 0.0001,
      }
    })

    // 塔底：一块贴着地面的暗色板，把塔"焊"在环上
    const baseGeometry = new THREE.BoxGeometry(SEG_SIZE + 0.45, 0.12, SEG_SIZE + 0.45)
    const baseMaterial = new THREE.MeshStandardMaterial({ color: palette.ring, transparent: true, opacity: 0.5 })
    const base = new THREE.Mesh(baseGeometry, baseMaterial)
    base.position.y = 0.06
    disposables.push(baseGeometry, baseMaterial)
    group.add(base)

    towers.set(bucket.key, {
      key: bucket.key,
      label: bucket.label,
      group,
      segments,
      baseValue: bucket.value,
      home,
      focus,
    })
    scene.add(group)
  }

  computeUnit()
  applyEvolve()
  // 首次渲染直接用目标高度，不要"从 0 长起来"
  for (const tower of towers.values()) {
    for (const seg of tower.segments) {
      seg.currentH = seg.targetH
    }
  }
  applySelection()
}

/**
 * 定标：把「所有桶里市值 + 全窗口投入」的最大值映射到 MAX_TOWER_H，**画面全程固定**。
 *
 * 为什么用最终高度而不是初始高度：演变里各桶的增幅可能差一个数量级（每天定投的股基一年能翻几倍、
 * 放着的债基一倍不涨），按初始定标会让镜头要么飞出天际、要么把塔压成一条线。
 * 按最终定标时：真实账号初始塔高约是满高的 25%~40%、演变后顶到满高（成长看得见）；
 * 极端账号（刚建仓、定投远大于持仓）表现为「从小到大长满整框」，构图始终稳定。
 */
function computeUnit() {
  const maxFinal = Math.max(1, ...[...towers.values()].map((tower) =>
    tower.segments.reduce((sum, seg) => sum + seg.assetValue + (seg.cumulative.at(-1) ?? 0), 0)))
  unit = MAX_TOWER_H / maxFinal
}

/** 按 evolveMonth 重新算每一截的目标高度（增量 = 该资产到那个月末的累计投入） */
function applyEvolve() {
  const monthIndex = Math.max(0, Math.min(12, props.evolveMonth)) - 1
  for (const tower of towers.values()) {
    for (const seg of tower.segments) {
      const invested = monthIndex >= 0 ? (seg.cumulative[monthIndex] ?? 0) : 0
      seg.targetH = Math.max(0.0001, (seg.assetValue + invested) * unit)
    }
  }
}

/** 选中态：非选中的塔淡出，选中的平移到左侧 */
function applySelection() {
  for (const tower of towers.values()) {
    const dimmed = props.selected !== null && props.selected !== tower.key
    for (const seg of tower.segments) {
      const material = seg.mesh.material as THREE.MeshStandardMaterial
      material.opacity = dimmed ? 0.08 : 0.92
      // 描边是 mesh 的子节点，遍历 children 一起调透明度
      for (const child of seg.mesh.children) {
        if (child instanceof THREE.LineSegments) {
          ;(child.material as THREE.LineBasicMaterial).opacity = dimmed ? 0.08 : 0.95
        }
      }
    }
  }
}

let lights: THREE.Object3D[] = []

/**
 * 重建灯光：给足环境光（有些机器上只有方向光时盒子会渲染成黑块），
 * 半球光的地面色用环线色 —— 换主题时要重来一遍（颜色是取色那一刻定的）。
 */
function rebuildLights() {
  if (!scene) return
  for (const light of lights) scene.remove(light)
  const ambient = new THREE.AmbientLight(0xffffff, 0.85)
  const hemisphere = new THREE.HemisphereLight(0xffffff, palette.ring, 0.9)
  const key = new THREE.DirectionalLight(0xffffff, 1.1)
  key.position.set(6, 14, 9)
  lights = [ambient, hemisphere, key]
  for (const light of lights) scene.add(light)
}

/** 按下位置（左键）：松手时用它判断这次是「点击」还是「拖视角」 */
let downX = 0
let downY = 0
let pressed = false

function onPointerDown(event: PointerEvent) {
  pressed = event.button === 0
  downX = event.clientX
  downY = event.clientY
}

function onPointerUp(event: PointerEvent) {
  if (!pressed || event.button !== 0) return
  pressed = false
  // 拖动过（旋转 / 平移）不算点击，否则每次转完视角都会顺手把选中状态清掉
  if (Math.hypot(event.clientX - downX, event.clientY - downY) > CLICK_SLOP) return
  pickTower(event)
}

/** 点击（非拖动）时用射线找塔：命中哪个桶就选哪个，点了空白处就取消选中 */
function pickTower(event: PointerEvent) {
  if (!camera || !host.value) return
  const rect = host.value.getBoundingClientRect()
  const ndc = new THREE.Vector2(
    ((event.clientX - rect.left) / rect.width) * 2 - 1,
    -((event.clientY - rect.top) / rect.height) * 2 + 1,
  )
  const raycaster = new THREE.Raycaster()
  raycaster.setFromCamera(ndc, camera)
  const hit = raycaster.intersectObjects(pickables, false)[0]
  if (!hit) {
    emit('select', null)
    return
  }
  for (const [key, tower] of towers) {
    if (tower.segments.some((seg) => seg.mesh === hit.object)) {
      emit('select', key)
      return
    }
  }
}

/** 视角是否离开默认机位（OrbitControls 每次 change 都算一遍，只在真假翻转时写 ref） */
function syncViewMoved() {
  if (!camera || !controls) return
  const moved =
    camera.position.distanceTo(DEFAULT_CAMERA_POS) > 0.05 ||
    controls.target.distanceTo(DEFAULT_TARGET) > 0.05
  if (moved !== viewMoved.value) viewMoved.value = moved
}

function resetView() {
  controls?.reset()
  controls?.update()
  camera?.updateMatrixWorld()
  syncViewMoved()
}

function animate() {
  frame = requestAnimationFrame(animate)
  if (!renderer || !scene || !camera || !host.value) return

  // 0) 相机：阻尼补间与目标点收敛都在 update 里推进；矩阵要跟着刷新，
  //    否则这一帧的标签投影用的是上一帧的机位（转视角时标签会拖后一帧）
  controls?.update()
  camera.updateMatrixWorld()

  // 1) 每一截的高度与位置（自下而上重新摞）
  for (const tower of towers.values()) {
    let y = 0.12
    for (const seg of tower.segments) {
      seg.currentH += (seg.targetH - seg.currentH) * 0.12
      const h = Math.max(0.0001, seg.currentH)
      seg.mesh.scale.y = h
      seg.mesh.position.y = y + h / 2
      y += h + 0.02
    }
    // 2) 选中时平移（lerp 到 home / focus）
    const target = props.selected === tower.key ? tower.focus : tower.home
    tower.group.position.lerp(target, 0.12)
  }

  // 3) 雾（远近视差）跟着镜头距离走：拉远时整组塔会一起变淡，把雾半径钉在当前机位上，
  //    近的塔始终清晰、远的塔只是略微发灰 —— 与默认机位下的 26/46 完全一致
  const camDistance = camera.position.distanceTo(controls?.target ?? DEFAULT_TARGET)
  if (scene.fog instanceof THREE.Fog) {
    scene.fog.near = camDistance + 5
    scene.fog.far = camDistance + 24
  }

  // 4) 标签：把塔顶投到屏幕坐标
  const width = host.value.clientWidth
  const height = host.value.clientHeight
  const forward = camera.getWorldDirection(tmpForward)
  const next: typeof labels.value = []
  for (const tower of towers.values()) {
    if (props.selected !== null && props.selected !== tower.key) continue
    tmpTop.copy(tower.group.position)
    tmpTop.y = tower.segments.reduce((sum, seg) => sum + Math.max(0.0001, seg.currentH) + 0.02, 0.12)
    // 塔转到镜头背后时 project 会把坐标翻到画面另一侧，这种标签直接不画
    if (tmpDelta.subVectors(tmpTop, camera.position).dot(forward) <= 0) continue
    tmpTop.project(camera)
    const invested = props.evolveMonth > 0
      ? tower.segments.reduce((sum, seg) => sum + (seg.cumulative[props.evolveMonth - 1] ?? 0), 0)
      : 0
    next.push({
      key: tower.key,
      label: tower.label,
      value: formatWan(tower.baseValue + invested),
      x: (tmpTop.x * 0.5 + 0.5) * width,
      y: (-tmpTop.y * 0.5 + 0.5) * height,
    })
  }
  labels.value = next

  renderer.render(scene, camera)
}

function resize() {
  if (!renderer || !camera || !host.value) return
  const width = host.value.clientWidth
  const height = host.value.clientHeight
  if (width === 0 || height === 0) return
  camera.aspect = width / height
  camera.updateProjectionMatrix()
  renderer.setSize(width, height, false)
}

onMounted(() => {
  if (!host.value) return
  const width = host.value.clientWidth || 900
  const height = host.value.clientHeight || 520

  renderer = new THREE.WebGLRenderer({ antialias: true })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setSize(width, height, false)
  // 让 canvas 的显示尺寸由容器决定（而非 attribute 尺寸），
  // 避免「clientHeight → canvas.height → 显示高度 → clientHeight」的反馈循环把画布撑爆
  renderer.domElement.style.width = '100%'
  renderer.domElement.style.height = '100%'
  host.value.appendChild(renderer.domElement)

  scene = new THREE.Scene()
  scene.background = new THREE.Color(palette.background)
  scene.fog = new THREE.Fog(palette.background, 26, 46)

  camera = new THREE.PerspectiveCamera(42, width / height, 0.1, 120)
  // 抬高 + 拉远：前排（z 大）的塔底不能再被画布下沿裁掉
  camera.position.copy(DEFAULT_CAMERA_POS)
  camera.lookAt(DEFAULT_TARGET)

  // 自由摆位：左键转、右键（或 Shift+左键）平移、滚轮缩放。
  // 平移用「沿地面」的算法（screenSpacePanning=false）：目标点高度不变，镜头永远不会钻到地下。
  controls = new OrbitControls(camera, renderer.domElement)
  controls.target.copy(DEFAULT_TARGET)
  controls.cursor.copy(DEFAULT_TARGET)
  controls.enableDamping = true
  controls.dampingFactor = 0.08
  controls.rotateSpeed = 0.85
  controls.zoomSpeed = 0.9
  controls.panSpeed = 0.8
  controls.screenSpacePanning = false
  controls.minDistance = MIN_DISTANCE
  controls.maxDistance = MAX_DISTANCE
  controls.minPolarAngle = MIN_POLAR
  controls.maxPolarAngle = MAX_POLAR
  controls.maxTargetRadius = MAX_TARGET_RADIUS
  controls.cursorStyle = 'grab'
  // target0 是构造时（target 还是 0,0,0）记下的，配置完必须重新存一次，否则 reset() 回到原点
  controls.saveState()
  controls.addEventListener('change', syncViewMoved)

  rebuildLights()

  // 地面同心环（参考设计稿的氛围元素；不承载数据含义）
  for (const radius of [3.4, RING_RADIUS + 1.6, RING_RADIUS + 4.4, RING_RADIUS + 7.2]) {
    const points: THREE.Vector3[] = []
    for (let i = 0; i <= 96; i++) {
      const t = (i / 96) * Math.PI * 2
      points.push(new THREE.Vector3(Math.cos(t) * radius, 0, Math.sin(t) * radius))
    }
    const geometry = new THREE.BufferGeometry().setFromPoints(points)
    const material = new THREE.LineBasicMaterial({ color: palette.ring, transparent: true, opacity: 0.85 })
    disposables.push(geometry, material)
    scene.add(new THREE.Line(geometry, material))
  }

  host.value.addEventListener('pointerdown', onPointerDown)
  host.value.addEventListener('pointerup', onPointerUp)
  resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(host.value)
  buildScene(props.buckets)
  animate()
})

onBeforeUnmount(() => {
  cancelAnimationFrame(frame)
  resizeObserver?.disconnect()
  host.value?.removeEventListener('pointerdown', onPointerDown)
  host.value?.removeEventListener('pointerup', onPointerUp)
  controls?.removeEventListener('change', syncViewMoved)
  controls?.dispose()
  controls = undefined
  for (const item of disposables) item.dispose()
  renderer?.dispose()
  if (renderer?.domElement.parentElement) {
    renderer.domElement.parentElement.removeChild(renderer.domElement)
  }
})

watch(() => props.buckets, (next) => buildScene(next), { deep: true })
// 主题切换：重新取一组色 → 场景里的背景/环线/塔色/灯光全部换掉
watch(isDark, () => {
  palette = scenePalette()
  if (scene && camera) {
    scene.background = new THREE.Color(palette.background)
    scene.fog = new THREE.Fog(palette.background, 26, 46)
    rebuildLights()
  }
  buildScene(props.buckets)
})
watch(() => props.evolveMonth, () => applyEvolve())
watch(() => props.selected, () => {
  // 落位点按「点这一下时的视线」重算（视角被拖过也停在画面左侧）
  const tower = props.selected === null ? undefined : towers.get(props.selected)
  if (tower) tower.focus.copy(computeFocus())
  applySelection()
})
</script>

<template>
  <div ref="host" class="relative w-full select-none overflow-hidden">
    <!-- 桶标签：HTML 覆盖层，每帧跟着塔顶投影走 -->
    <div
      v-for="item in labels"
      :key="item.key"
      class="pointer-events-none absolute -translate-x-1/2 whitespace-nowrap text-center font-mono tabular-nums text-foreground"
      :style="{ left: `${item.x}px`, top: `${item.y - 44}px` }"
    >
      <p class="text-xs text-muted-foreground">{{ item.label }}</p>
      <p class="text-sm font-semibold">{{ item.value }}</p>
    </div>

    <!-- 视角被拖过 / 缩放过才出现：一键回到默认机位（拖拽的点按不能被当成选塔） -->
    <Button
      v-if="viewMoved"
      variant="outline"
      size="xs"
      class="absolute bottom-4 left-4 bg-background/85 backdrop-blur-sm"
      @pointerdown.stop
      @click="resetView"
    >
      <Focus />
      复位视角
    </Button>
  </div>
</template>
