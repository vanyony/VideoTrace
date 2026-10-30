<template>
  <section id="creation-workspace" class="creation-workspace" aria-labelledby="creation-heading">
    <header class="creation-header"><div><p class="page-eyebrow">SHORT VIDEO CREATOR</p><h2 id="creation-heading">短视频创作</h2><p>{{ media.filename }}</p></div><button @click="emit('close')">关闭创作区</button></header>
    <p v-if="error" role="alert" class="creation-error">{{ error }} <button :disabled="loading" @click="open(media)">重新加载</button></p>
    <div v-if="sourceUrl || sourceError" class="creation-artifact">
      <p>原视频 · 时间戳证据</p><p v-if="sourceError" role="alert">{{ sourceError }}</p>
      <video v-if="sourceUrl" ref="sourcePlayer" :src="sourceUrl" controls playsinline preload="metadata" aria-label="原视频证据" @error="sourceError = '原视频暂时无法播放，请再次点击时间戳刷新链接。'" />
    </div>
    <p v-if="!eligibility" role="status">{{ loading ? '正在检查素材与历史任务…' : '暂时无法检查素材，请重新加载。' }}</p>
    <div v-else-if="!eligibility.eligible" class="creation-notice" role="status">
      <p>{{ sourceStatus?.message || eligibility.reason || '准备原视频素材后即可开始创作。' }}</p>
      <button :disabled="preparing || ['QUEUED', 'PROCESSING'].includes(sourceStatus?.state)" @click="prepareSource">
        {{ preparing || ['QUEUED', 'PROCESSING'].includes(sourceStatus?.state) ? '正在准备素材…' : sourceStatus?.state === 'FAILED' ? '重试素材准备' : '准备原视频素材' }}
      </button>
      <button type="button" :disabled="preparing" @click="refreshSource">刷新状态</button>
    </div>
    <form class="creation-form" @submit.prevent="create">
      <label>创作需求<textarea v-model="requirement" required maxlength="2000" rows="3" placeholder="面向普通消费者，总结发布会最值得关注的三个变化，避免宣传语气。" :disabled="!canCreate" /></label>
      <label>成片时长<select v-model.number="duration" :disabled="!canCreate"><option :value="15">15 秒</option><option :value="30">30 秒</option><option :value="45">45 秒</option><option :value="60">60 秒</option></select><span>分镜总时长以此处选择为准；如需改变请先调整时长，再开始新对话。</span></label>
      <button class="creation-primary" :disabled="!canCreate || !requirement.trim()">开始创作对话</button>
    </form>
    <label v-if="tasks.length" class="creation-history">创作任务<select :value="task?.taskId" :disabled="loading" @change="selectTask($event.target.value)"><option v-for="item in tasks" :key="item.taskId" :value="item.taskId">{{ item.storyboard?.title || item.taskId }} · {{ creationLabels[item.status] || item.status }}</option></select></label>
    <div v-if="task" class="creation-result">
      <div class="creation-header"><h3>{{ task.storyboard?.title || '创作任务' }}</h3><span role="status" aria-live="polite">{{ creationLabels[task.status] || task.status }} · 版本 {{ task.revision || 0 }}</span></div>
      <p v-if="task.summary" role="status">{{ task.summary }}</p>
      <aside v-if="task.previewReview" class="creation-notice" role="status" aria-label="分镜视觉检查结果">
        <strong>{{ task.previewReview.status === 'needs_attention' ? '当前分镜仍需人工核验' : task.previewReview.status === 'suggestions' ? '抽样检查有改进建议' : '抽样画面暂未发现明确问题' }}</strong>
        <p>{{ task.previewReview.summary }}</p>
        <p v-if="task.previewReview.fallbackReason">检查或修复预算已用尽，当前保留最近成功检查的草稿，未解决的问题仍需处理。</p>
        <ul v-if="task.previewReview.issues?.length"><li v-for="(issue, index) in task.previewReview.issues" :key="index">
          {{ issue.sceneId }} · {{ issue.severity === 'warning' ? '警告' : '建议' }}：{{ issue.problem }}<br>建议：{{ issue.suggestion }}
        </li></ul>
        <p>检查仅覆盖抽样画面，请核对原视频证据与最终分镜后再确认渲染。</p>
      </aside>
      <p v-if="task.sourceDeleted" class="creation-notice">源视频已删除，已生成作品仍可访问，无法继续修改或渲染。</p>
      <p v-if="task.error" class="creation-error" role="alert">{{ typeof task.error === 'string' ? task.error : task.error.message || '创作失败' }}</p>
      <button v-if="task.status === 'FAILED' && task.retryable" :disabled="loading || task.sourceDeleted" @click="submit('retry')">重试失败步骤</button>
      <ol v-if="task.messages?.length" class="creation-messages" aria-label="创作对话记录"><li v-for="(message, index) in task.messages" :key="message.messageId || index" :class="message.role === 'user' ? 'creation-message-user' : ''"><strong>{{ message.role === 'user' ? '你' : '创作助手' }}</strong><p>{{ message.content }}</p></li></ol>
      <template v-if="task.storyboard">
        <p>受众：{{ task.storyboard.targetAudience }} · 音乐：{{ task.storyboard.musicId === 'none' ? '关闭' : '轻音乐' }}</p>
        <div class="creation-scenes"><article v-for="(scene, index) in task.storyboard.scenes" :key="scene.sceneId" class="creation-scene">
          <label><input v-model="targetScenes" type="checkbox" :value="scene.sceneId" :disabled="!canEdit" /> 仅修改此场景</label>
          <p class="page-eyebrow">场景 {{ index + 1 }} · {{ scene.durationMs / 1000 }} 秒 · {{ templates[scene.template] || scene.template }}</p>
          <h4>{{ scene.headline }}</h4><ul><li v-for="(point, pointIndex) in scene.points" :key="pointIndex">{{ point }}</li></ul>
          <p v-if="scene.visual" class="creation-source">二维图解：{{ visualLayouts[scene.visual.layout] }} · {{ scene.visual.theme }} · {{ scene.visual.motion }}<br>{{ scene.visual.items.map(item => item.label).join(' / ') }}</p>
          <p v-if="scene.canvas" class="creation-source">二维画布：{{ scene.canvas.elements.length }} 个元素 · {{ scene.canvas.elements.reduce((sum, item) => sum + (item.keyframes?.length || 0), 0) }} 个关键帧<span v-if="scene.canvas.continuePrevious"> · 延续上一幕</span><br>{{ scene.canvas.elements.filter(item => item.kind === 'sprite').map(item => ({ sun: '太阳', earth: '地球', cloud: '云', droplet: '水滴', factory: '工厂', truck: '卡车' }[item.sprite] || item.sprite)).join(' / ') }}<br>{{ scene.canvas.elements.filter(item => item.text).map(item => item.text).join(' / ') }}</p>
          <p v-if="scene.motion" class="creation-source">{{ scene.motion.style === 'launch' ? '发布会拼版' : '动态文字' }} · {{ scene.motion.words.join(' / ') }} · {{ scene.motion.transition }}</p>
          <p class="creation-source">原视频证据：<button v-for="reference in scene.sourceRefs" :key="reference" type="button" @click="seekSource(reference)">{{ sourceTimestamp(reference) }}</button></p>
        </article></div>
        <button class="creation-primary" :disabled="!canEdit" @click="submit('render', { baseRevision: task.revision })">确认版本 {{ task.revision }}，生成视频</button>
      </template>
      <form class="creation-form" @submit.prevent="sendMessage"><label>继续交流<textarea v-model="instruction" rows="2" required maxlength="2000" :disabled="!canEdit" placeholder="可以讨论创作方向、提问，或明确要求生成及修改分镜。" /></label><p v-if="targetScenes.length">已选 {{ targetScenes.length }} 个场景，修改要求仅作用于所选场景。</p><p v-else>助手会根据消息判断是讨论、生成还是修改分镜。生成视频仍需点击确认按钮。</p><button :disabled="!canEdit || !instruction.trim()">发送</button></form>
      <div v-if="artifactRevision(task) != null" class="creation-artifact"><p>成片版本 {{ artifactRevision(task) }}</p><button :disabled="loading" @click="loadArtifact">{{ artifact ? '刷新成片链接' : '加载成片预览与下载' }}</button><template v-if="artifact"><video :src="artifact.url" controls playsinline preload="metadata" aria-label="生成的短视频" /><a :href="artifact.url" target="_blank" rel="noopener" download>下载 MP4</a><p>链接短时有效，过期后请刷新。</p></template></div>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { apiRequest, captureAuthSession } from './api.js'
import { useCreationWorkspace, creationLabels, sourceTimestamp, artifactRevision } from './useCreationWorkspace.js'
const props = defineProps({ media: { type: Object, required: true } })
const emit = defineEmits(['close'])
const workspace = useCreationWorkspace()
const { eligibility, tasks, task, loading, error, artifact, sourceStatus, preparing, prepareSource, refreshSource, open, close, select, submit, loadArtifact } = workspace
const requirement = ref(''), duration = ref(45), instruction = ref(''), targetScenes = ref([])
const sourceUrl = ref(''), sourceError = ref(''), sourcePlayer = ref(null)
let sourceVersion = 0
async function seekSource(reference) {
  const match = /^e_(\d+)_(\d+)$/.exec(reference)
  if (!match) return
  const version = ++sourceVersion, session = captureAuthSession(), mediaId = props.media.id
  sourceError.value = ''
  try {
    if (!sourceUrl.value || sourcePlayer.value?.error) {
      const response = await apiRequest(`/media/playback?id=${encodeURIComponent(mediaId)}`)
      if (!response.ok) throw new Error(await response.text())
      const url = await response.json()
      if (version !== sourceVersion || !session() || props.media.id !== mediaId) return
      sourceUrl.value = url
    }
    await nextTick()
    if (version !== sourceVersion || !session()) return
    const player = sourcePlayer.value
    if (!player) return
    const seek = () => {
      if (version !== sourceVersion || !session()) return
      const maximum = Number.isFinite(player.duration) ? Math.max(0, player.duration - 0.1) : Infinity
      player.currentTime = Math.min(Number(match[1]) / 1000, maximum)
      player.play().catch(() => {})
    }
    if (player.readyState) seek()
    else player.addEventListener('loadedmetadata', seek, { once: true })
    player.scrollIntoView({ behavior: 'smooth', block: 'center' })
  } catch (cause) { if (version === sourceVersion && session()) sourceError.value = cause.message || '原视频暂时无法播放' }
}
const templates = { title: '标题卡', points: '要点卡', flow: '流程卡' }
const visualLayouts = { pipeline: '串联流程', split: '分发', merge: '汇聚', compare: '对比', bars: '比例柱图', orbit: '中心关系' }
const canCreate = computed(() => eligibility.value?.eligible && !loading.value)
const canEdit = computed(() => !loading.value && !task.value?.sourceDeleted && ['AWAITING_CONFIRMATION', 'COMPLETED', 'DISCUSSED'].includes(task.value?.status))
function create() { if (canCreate.value && requirement.value.trim()) submit('create', { mediaId: props.media.id, requirement: requirement.value.trim(), durationSeconds: duration.value }) }
async function sendMessage() {
  if (!canEdit.value || !instruction.value.trim()) return
  await submit('messages', { instruction: instruction.value.trim(), baseRevision: task.value.revision, ...(targetScenes.value.length ? { targetSceneIds: targetScenes.value } : {}) })
  if (!error.value) instruction.value = ''
}
function selectTask(id) { const selected = tasks.value.find(item => String(item.taskId) === id); if (selected) select(selected) }
watch(() => [task.value?.taskId, task.value?.revision], () => { targetScenes.value = []; instruction.value = '' })
watch(() => props.media.id, () => { sourceVersion++; sourceUrl.value = ''; sourceError.value = ''; open(props.media) }, { immediate: true })
onMounted(() => document.addEventListener('visibilitychange', workspace.reschedule))
onUnmounted(() => { sourceVersion++; document.removeEventListener('visibilitychange', workspace.reschedule); close() })
</script>

<style scoped>
.creation-workspace { margin: 28px 0; padding: 28px; border: 1px solid var(--line); border-radius: 18px; background: white; scroll-margin-top: 90px; }
.creation-header { display:flex; justify-content:space-between; gap:16px; align-items:center; margin-bottom:16px; flex-wrap:wrap; }
p { margin:10px 0; color:var(--muted); overflow-wrap:anywhere; }
button, select, textarea, a { border:1px solid var(--line); border-radius:8px; padding:10px 14px; background: white; color:inherit; }
label { display:flex; flex-direction:column; gap:8px; font-size:14px; }
.creation-form { display:flex; flex-wrap:wrap; gap:16px; align-items:end; margin:20px 0; }
.creation-form label:first-child { flex:1 1 400px; }
textarea { resize:vertical; width:100%; }
.creation-primary { background:var(--blue); color:white; border-color:var(--blue); }
.creation-messages { list-style:none; padding:0; display:grid; gap:12px; }.creation-messages li { padding:14px; border-radius:10px; background:var(--soft); }.creation-messages p { white-space:pre-wrap; }.creation-messages .creation-message-user { border-left:3px solid var(--blue); }
.creation-history { margin:24px 0; }
.creation-scenes { display:grid; grid-template-columns:repeat(auto-fit,minmax(min(260px,100%),1fr)); gap:16px; margin:20px 0; }
.creation-scene { padding:20px; border:1px solid var(--line); background:var(--soft); border-radius:12px; }
.creation-scene label { flex-direction:row; align-items:center; color:var(--muted); }
.creation-scene h4 { margin:14px 0; overflow-wrap:anywhere; }
.creation-scene li { margin:8px 0; overflow-wrap:anywhere; }
.creation-source { font-size:12px; }
.creation-error { color:#b42318; }.creation-notice { background:#fff8e8; padding:14px; border-radius:8px; }
.creation-artifact { display:flex; flex-direction:column; align-items:start; gap:14px; margin-top:24px; }
video { width:100%; max-width:360px; max-height:640px; border-radius:12px; background:#111; }
@media(max-width:600px) { .creation-workspace { padding:18px; } }
</style>
