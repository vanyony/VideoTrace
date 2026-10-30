import assert from 'node:assert/strict'
import test, { beforeEach } from 'node:test'
import { setAuthToken } from './api.js'
import { useMediaProcessingWorkspace } from './useMediaProcessingWorkspace.js'

beforeEach(() => {
  const storage = new Map()
  globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
  setAuthToken('token')
})
const json = data => Response.json({ code: 0, message: 'success', data })
function setup() {
  const streams = { start() {}, stopMedia() {} }
  return { streams, workspace: useMediaProcessingWorkspace({ taskStreams: streams, findMediaItem: () => ({ filename: 'source.mp4' }) }) }
}
test('existing source transcript opens without starting another paid transcription', async () => {
  const requests = []
  globalThis.fetch = async (url, options) => { requests.push([url, options.method]); return json({ state: 'COMPLETED', result: '原视频文字' }) }
  const { workspace } = setup()
  await workspace.transcribe(7)
  assert.equal(workspace.sidebar.value.content, '原视频文字')
  assert.equal(workspace.sidebar.value.loading, false)
  assert.equal(requests.length, 1)
  assert.ok(requests[0][0].includes('/media-processing/transcription-status'))
})
test('closing a transcript while its response is pending cannot repopulate the panel', async () => {
  let resolve
  globalThis.fetch = () => new Promise(done => { resolve = done })
  const { workspace } = setup()
  const pending = workspace.transcribe(7)
  workspace.resetWorkspace()
  resolve(json({ state: 'COMPLETED', result: '迟到内容' }))
  await pending
  assert.equal(workspace.sidebar.value.visible, false)
  assert.equal(workspace.sidebar.value.content, '')
})
