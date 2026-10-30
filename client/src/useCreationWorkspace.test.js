import assert from 'node:assert/strict'
import test, { beforeEach } from 'node:test'
import { useCreationWorkspace, sourceTimestamp, isCreationActive, artifactRevision } from './useCreationWorkspace.js'
import { setAuthToken, clearAuthToken } from './api.js'

const json = data => Response.json({ code: 0, message: 'success', data })
const item = { id: 7, filename: 'source.mp4' }
const draft = { taskId: 't1', mediaId: 7, revision: 1, status: 'DRAFTING' }
beforeEach(() => {
  const storage = new Map()
  globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
  setAuthToken('token')
})
function setup() {
  const scheduled = [], cancelled = []
  let hidden = false
  const workspace = useCreationWorkspace({ hidden: () => hidden, schedule: (fn, delay) => { scheduled.push({ fn, delay }); return scheduled.length }, cancel: id => cancelled.push(id) })
  return { workspace, scheduled, cancelled, hide: () => { hidden = true } }
}
function mockFetch(snapshot = () => draft) {
  globalThis.fetch = async url => json(url.includes('source-status') ? { state: 'COMPLETED' } : url.includes('eligibility') ? { eligible: true, sourceVersion: 2 } : url.includes('?mediaId') ? [draft] : snapshot())
}

test('source preparation resumes from durable status and enables creation on completion', async () => {
  let state = 'NOT_STARTED', submissions = 0
  globalThis.fetch = async (url, options = {}) => {
    if (url.includes('prepare-source')) { submissions++; state = 'QUEUED'; return json({ state }) }
    if (url.includes('source-status')) return json({ state, message: state })
    if (url.includes('eligibility')) return json({ eligible: state === 'COMPLETED' })
    return json([])
  }
  const { workspace, scheduled, hide } = setup()
  await workspace.open(item)
  assert.equal(workspace.eligibility.value.eligible, false)
  await workspace.prepareSource()
  assert.equal(submissions, 1)
  assert.equal(workspace.sourceStatus.value.state, 'QUEUED')
  hide(); workspace.reschedule()
  assert.equal(scheduled.at(-1).delay, 15000)
  state = 'COMPLETED'
  await scheduled.at(-1).fn()
  assert.equal(workspace.eligibility.value.eligible, true)
  assert.equal(workspace.sourceStatus.value.state, 'COMPLETED')
  workspace.close()
})

test('lost preparation acceptance is recovered by reading canonical status', async () => {
  let state = 'NOT_STARTED'
  globalThis.fetch = async (url, options = {}) => {
    if (url.includes('prepare-source')) { state = 'PROCESSING'; throw new TypeError('connection lost') }
    if (url.includes('source-status')) return json({ state })
    if (url.includes('eligibility')) return json({ eligible: false })
    return json([])
  }
  const { workspace, scheduled } = setup()
  await workspace.open(item)
  await workspace.prepareSource()
  assert.equal(workspace.sourceStatus.value.state, 'PROCESSING')
  assert.equal(workspace.preparing.value, false)
  assert.ok(scheduled.length)
  workspace.close()
})

test('late source status cannot restore state after closing and reopening the same media', async () => {
  let delayed = false, resolve
  globalThis.fetch = async url => {
    if (url.includes('source-status')) {
      if (delayed) return new Promise(done => { resolve = done })
      return json({ state: 'NOT_STARTED' })
    }
    return json(url.includes('eligibility') ? { eligible: false } : [])
  }
  const { workspace } = setup()
  await workspace.open(item)
  delayed = true
  const old = workspace.refreshSource()
  workspace.close(); delayed = false
  await workspace.open(item)
  resolve(json({ state: 'COMPLETED' }))
  await old
  assert.equal(workspace.sourceStatus.value.state, 'NOT_STARTED')
  assert.equal(workspace.eligibility.value.eligible, false)
  workspace.close()
})

test('polling slows while hidden and stops at confirmation and on close', async () => {
  let snapshot = draft
  mockFetch(() => snapshot)
  const { workspace, scheduled, cancelled, hide } = setup()
  await workspace.open(item)
  assert.equal(scheduled.at(-1).delay, 2500)
  hide(); workspace.reschedule()
  assert.equal(scheduled.at(-1).delay, 15000)
  snapshot = { ...draft, status: 'AWAITING_CONFIRMATION' }
  await workspace.refresh()
  const count = scheduled.length
  workspace.reschedule()
  assert.equal(scheduled.length, count)
  workspace.close()
  assert.equal(workspace.task.value, null)
  assert.ok(cancelled.length)
})

test('revision submission carries version, stable targets and idempotency key', async () => {
  mockFetch()
  const { workspace } = setup()
  await workspace.open(item)
  let submitted
  globalThis.fetch = async (url, options) => {
    if (options.method === 'POST') { submitted = JSON.parse(options.body); return json({ ...draft, status: 'REVISING' }) }
    return json({ ...draft, revision: 2, status: 'AWAITING_CONFIRMATION' })
  }
  await workspace.submit('messages', { instruction: '只修改第一场', baseRevision: 1, targetSceneIds: ['s_01'] })
  assert.equal(submitted.baseRevision, 1)
  assert.deepEqual(submitted.targetSceneIds, ['s_01'])
  assert.match(submitted.requestId, /^[0-9a-f-]{36}$/)
  assert.equal(workspace.task.value.revision, 2)
})

test('creation submits the fifteen second selection unchanged', async () => {
  mockFetch()
  const { workspace } = setup()
  await workspace.open(item)
  let submitted
  globalThis.fetch = async (url, options = {}) => {
    if (options.method === 'POST') { submitted = JSON.parse(options.body); return json(draft) }
    return json(draft)
  }
  await workspace.submit('create', { mediaId: item.id, requirement: '15秒总结', durationSeconds: 15 })
  assert.equal(submitted.durationSeconds, 15)
  workspace.close()
})

test('late response after logout or closing cannot restore private tasks', async () => {
  let resolve
  globalThis.fetch = () => new Promise(done => { resolve = done })
  const { workspace } = setup()
  workspace.task.value = draft
  const pending = workspace.refresh()
  clearAuthToken(); workspace.close()
  resolve(json(draft)); await pending
  assert.equal(workspace.task.value, null)
})

test('old polling result cannot overwrite a revision operation', async () => {
  mockFetch()
  const { workspace } = setup()
  await workspace.open(item)
  let resolve
  globalThis.fetch = () => new Promise(done => { resolve = done })
  const pending = workspace.refresh()
  globalThis.fetch = async () => json({ ...draft, revision: 2, status: 'AWAITING_CONFIRMATION' })
  await workspace.submit('messages', { instruction: 'change', baseRevision: 1 })
  resolve(json(draft)); await pending
  assert.equal(workspace.task.value.revision, 2)
})

test('source evidence timestamps retain references and handle long videos', () => {
  assert.equal(sourceTimestamp('e_12000_65000'), '00:12–01:05')
  assert.equal(sourceTimestamp('e_3600000_3605000'), '60:00–60:05')
  assert.equal(sourceTimestamp('e_12'), 'e_12')
})

test('retrying a create after losing its response reuses the accepted request identity', async () => {
  const { workspace } = setup()
  let acceptedId, calls = 0
  globalThis.fetch = async (url, options) => {
    if (options.method !== 'POST') return json(draft)
    const body = JSON.parse(options.body)
    calls++
    if (calls === 1) { acceptedId = body.requestId; throw new TypeError('response lost after acceptance') }
    assert.equal(body.requestId, acceptedId)
    return json(draft)
  }
  const body = { mediaId: 7, requirement: '重点总结', durationSeconds: 45 }
  await workspace.submit('create', body)
  assert.equal(workspace.task.value, null)
  await workspace.submit('create', { ...body })
  assert.equal(workspace.task.value.taskId, 't1')
  assert.equal(calls, 2)
})

test('changing create payload after a lost response starts a different request identity', async () => {
  const { workspace } = setup()
  const ids = []
  globalThis.fetch = async (url, options) => {
    if (options.method !== 'POST') return json(draft)
    ids.push(JSON.parse(options.body).requestId)
    if (ids.length === 1) throw new TypeError('response lost')
    return json(draft)
  }
  await workspace.submit('create', { mediaId: 7, requirement: 'first' })
  await workspace.submit('create', { mediaId: 7, requirement: 'changed' })
  assert.notEqual(ids[0], ids[1])
})

test('eligibility failures remain visible after selecting and polling history', async () => {
  globalThis.fetch = async url => url.includes('eligibility')
    ? Response.json({ code: 500, message: '素材检查不可用', data: null }, { status: 503 })
    : json(url.includes('?mediaId') ? [draft] : draft)
  const { workspace } = setup()
  await workspace.open(item)
  assert.equal(workspace.task.value.taskId, 't1')
  assert.equal(workspace.error.value, '素材检查不可用')
  await workspace.refresh()
  assert.equal(workspace.error.value, '素材检查不可用')
})


test('conversation creation and continuation use distinct routes and preserve discussion history', async () => {
  const { workspace, scheduled } = setup()
  const calls = []
  const snapshot = { ...draft, revision: 0, status: 'DISCUSSED', messages: [{ role: 'user', content: '先讨论方向' }, { role: 'assistant', content: '可以从受众开始' }] }
  globalThis.fetch = async (url, options) => { if (options.method === 'POST') calls.push({ url, body: JSON.parse(options.body) }); return json(snapshot) }
  await workspace.submit('create', { mediaId: 7, requirement: '先讨论方向', durationSeconds: 45 })
  assert.equal(calls[0].url, '/creation/conversations')
  assert.equal(calls[0].body.durationSeconds, 45)
  assert.equal(isCreationActive(workspace.task.value), false)
  assert.equal(scheduled.length, 0)
  await workspace.submit('messages', { instruction: '面向学生', baseRevision: 0 })
  assert.equal(calls[1].url, '/creation/tasks/t1/messages')
  assert.equal(calls[1].body.baseRevision, 0)
  assert.deepEqual(workspace.task.value.messages, snapshot.messages)
})

test('discussion can retrieve the previous artifact and legacy completed tasks still work', async () => {
  const { workspace } = setup()
  workspace.task.value = { ...draft, revision: 3, status: 'DISCUSSED', artifactRevision: 2 }
  let requested
  globalThis.fetch = async url => { requested = url; return json({ url: 'https://example.test/video.mp4' }) }
  await workspace.loadArtifact()
  assert.equal(requested, '/creation/tasks/t1/artifact?revision=2')
  assert.equal(workspace.artifact.value.url, 'https://example.test/video.mp4')
  assert.equal(artifactRevision({ status: 'COMPLETED', revision: 1 }), 1)
  assert.equal(artifactRevision({ status: 'DISCUSSED', revision: 0 }), null)
})


test('polling clears a previous preview only when the rendered artifact version changes', async () => {
  const { workspace } = setup()
  workspace.task.value = { ...draft, revision: 2, status: 'RENDERING', artifactRevision: 1 }
  const previous = { url: 'https://example.test/old.mp4' }
  workspace.artifact.value = previous
  let snapshot = { ...workspace.task.value }
  globalThis.fetch = async () => json(snapshot)
  await workspace.refresh()
  assert.deepEqual(workspace.artifact.value, previous)
  snapshot = { ...snapshot, status: 'COMPLETED', artifactRevision: 2 }
  await workspace.refresh()
  assert.equal(workspace.artifact.value, null)
  assert.equal(artifactRevision(workspace.task.value), 2)
})
