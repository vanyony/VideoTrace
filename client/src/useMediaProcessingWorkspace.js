import { ref } from 'vue'
import { apiRequest, captureAuthSession } from './api.js'

export function useMediaProcessingWorkspace({ taskStreams, showMessage = () => {}, findMediaItem = () => null }) {
  const sidebar = ref({ visible: false, type: 'text', title: '', mediaId: null, content: '', error: '', loading: false, statusMessage: '', streamOffline: false, streamRetry: 0 })
  let generation = 0
  function closeSidebar() { generation++; sidebar.value.visible = false }
  function resetWorkspace() { closeSidebar(); sidebar.value.content = ''; sidebar.value.loading = false; sidebar.value.mediaId = null }
  function discardMediaWorkspace(id) { taskStreams.stopMedia(id); if (sidebar.value.mediaId === id) resetWorkspace() }
  async function transcribe(id) {
    const version = ++generation, session = captureAuthSession()
    sidebar.value = { visible: true, type: 'text', title: `素材文字 · ${findMediaItem(id)?.filename || ''}`, mediaId: id, content: '', error: '', loading: true, statusMessage: '正在读取素材文字', streamOffline: false, streamRetry: 0 }
    const current = () => version === generation && session()
    function update(status) {
      if (!current()) return
      sidebar.value.streamOffline = false
      sidebar.value.statusMessage = status.message || ''
      if (status.state === 'COMPLETED' || status.state === 'FAILED') {
        sidebar.value.loading = false
        sidebar.value.content = status.result || ''
        sidebar.value.error = status.state === 'FAILED' ? status.message || '文字提取失败' : ''
      }
    }
    function follow() {
      taskStreams.start(id, 'text', '', `/media-processing/transcription-events?id=${id}`, status => {
        update(status)
        if (session() && ['COMPLETED', 'FAILED'].includes(status.state)) showMessage(status.message || '文字提取完成', status.state === 'FAILED')
      }, (error, attempt, terminal) => {
        if (!current()) return
        sidebar.value.streamOffline = !terminal; sidebar.value.streamRetry = attempt
        if (terminal) { sidebar.value.loading = false; sidebar.value.error = error.message || '无法获取文字提取状态' }
      })
    }
    try {
      const response = await apiRequest(`/media-processing/transcription-status?id=${id}`)
      if (!response.ok) throw new Error(await response.text())
      const status = await response.json()
      if (!current()) return
      if (status.state === 'COMPLETED') { update(status); return }
      if (!['QUEUED', 'PROCESSING'].includes(status.state)) {
        const accepted = await apiRequest(`/media-processing/transcribe?id=${id}`, { method: 'POST' })
        if (!accepted.ok && accepted.status !== 409) throw new Error(await accepted.text())
      }
      if (current()) follow()
    } catch (error) {
      if (current()) { sidebar.value.loading = false; sidebar.value.error = error.message }
    }
  }
  return { sidebar, transcribe, closeSidebar, resetWorkspace, discardMediaWorkspace }
}
