import { ref } from 'vue'
import { apiRequest, captureAuthSession } from './api.js'

export const creationLabels = { QUEUED: '等待执行', DRAFTING: '正在生成分镜', AWAITING_CONFIRMATION: '待确认分镜', REVISING: '正在修改分镜', RENDERING: '正在渲染视频', COMPLETED: '成片已完成', FAILED: '执行失败' }
export const isCreationActive = task => ['QUEUED', 'DRAFTING', 'REVISING', 'RENDERING'].includes(task?.status)
export function sourceTimestamp(reference) {
  const match = /^e_(\d+)_(\d+)$/.exec(reference)
  if (!match) return reference
  const format = ms => `${Math.floor(ms / 60000).toString().padStart(2, '0')}:${Math.floor(ms / 1000 % 60).toString().padStart(2, '0')}`
  return `${format(Number(match[1]))}–${format(Number(match[2]))}`
}

export function useCreationWorkspace({ hidden = () => document.hidden, schedule = setTimeout, cancel = clearTimeout } = {}) {
  const media = ref(null), eligibility = ref(null), tasks = ref([]), task = ref(null)
  const loading = ref(false), error = ref(''), artifact = ref(null)
  let generation = 0, timer, controller, pendingSubmission, eligibilityError = ''
  function stop() { cancel(timer); controller?.abort(); controller = null }
  function close() { generation++; stop(); pendingSubmission = null; eligibilityError = ''; media.value = null; task.value = null; tasks.value = []; artifact.value = null; loading.value = false; error.value = '' }
  async function request(path, body, signal) {
    const response = await apiRequest(path, body === undefined ? { signal } : { method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    if (!response.ok) {
      const failure = new Error(await response.text() || '创作服务暂时不可用')
      failure.definitive = true
      throw failure
    }
    return response.json()
  }
  function poll() {
    cancel(timer)
    if (!isCreationActive(task.value)) return
    timer = schedule(() => refresh(), hidden() ? 15000 : 2500)
  }
  async function refresh() {
    if (!task.value) return
    const id = task.value.taskId, version = generation, session = captureAuthSession()
    try {
      const latest = await request(`/creation/tasks/${encodeURIComponent(id)}`, undefined, controller?.signal)
      if (version !== generation || !session() || task.value?.taskId !== id) return
      task.value = latest
      tasks.value = tasks.value.map(item => item.taskId === id ? latest : item)
      error.value = eligibilityError
    } catch (cause) { if (version === generation && session() && cause.name !== 'AbortError') error.value = cause.message }
    if (version === generation && session()) poll()
  }
  async function select(selected) {
    generation++; stop(); controller = new AbortController(); task.value = selected; artifact.value = null; error.value = eligibilityError
    await refresh()
  }
  async function open(item) {
    close(); media.value = item; loading.value = true; eligibility.value = null
    controller = new AbortController()
    const version = generation, session = captureAuthSession(), signal = controller.signal
    const results = await Promise.allSettled([
      request(`/creation/eligibility?mediaId=${encodeURIComponent(item.id)}`, undefined, signal),
      request(`/creation/tasks?mediaId=${encodeURIComponent(item.id)}`, undefined, signal)
    ])
    if (version !== generation || !session()) return
    if (results[0].status === 'fulfilled') eligibility.value = results[0].value
    else if (results[0].reason.name !== 'AbortError') eligibilityError = results[0].reason.message
    if (results[1].status === 'fulfilled') tasks.value = results[1].value || []
    error.value = results.filter(result => result.status === 'rejected' && result.reason.name !== 'AbortError').map(result => result.reason.message).join('；')
    loading.value = false
    if (tasks.value.length) await select(tasks.value[0])
  }
  async function submit(action, body = {}) {
    if (loading.value) return
    generation++; stop(); controller = new AbortController()
    loading.value = true; error.value = ''
    const version = generation, session = captureAuthSession()
    try {
      const path = action === 'create' ? '/creation/tasks' : `/creation/tasks/${encodeURIComponent(task.value.taskId)}/${action}`
      const identity = JSON.stringify([path, body])
      if (pendingSubmission?.identity !== identity || !pendingSubmission.session()) pendingSubmission = { identity, session, requestId: crypto.randomUUID() }
      const pending = pendingSubmission
      const result = await request(path, { ...body, requestId: pending.requestId }, controller?.signal)
      if (version !== generation || !session()) return
      if (pendingSubmission === pending) pendingSubmission = null
      task.value = result
      artifact.value = null
      if (!tasks.value.some(item => item.taskId === result.taskId)) tasks.value.unshift(result)
      await refresh()
    } catch (cause) {
      if (version === generation && session() && cause.definitive) pendingSubmission = null
      if (version === generation && session() && cause.name !== 'AbortError') { error.value = cause.message; await refresh(); error.value = cause.message }
    } finally { if (version === generation) loading.value = false }
  }
  async function loadArtifact() {
    const version = generation, session = captureAuthSession(), current = task.value
    if (!current) return
    try {
      const result = await request(`/creation/tasks/${encodeURIComponent(current.taskId)}/artifact?revision=${current.revision}`, undefined, controller?.signal)
      if (version === generation && session() && task.value?.taskId === current.taskId && task.value?.revision === current.revision) { artifact.value = result; error.value = eligibilityError }
    } catch (cause) { if (version === generation && session() && cause.name !== 'AbortError') error.value = cause.message }
  }
  return { media, eligibility, tasks, task, loading, error, artifact, open, close, select, refresh, submit, loadArtifact, reschedule: poll }
}
