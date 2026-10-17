import assert from 'node:assert/strict'
import test, { beforeEach } from 'node:test'
import { useCreationWorkspace, sourceTimestamp } from './useCreationWorkspace.js'
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
  globalThis.fetch = async url => json(url.includes('eligibility') ? { eligible: true, sourceVersion: 2 } : url.includes('?mediaId') ? [draft] : snapshot())
}

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
  await workspace.submit('revisions', { instruction: '只修改第一场', baseRevision: 1, targetSceneIds: ['s_01'] })
  assert.equal(submitted.baseRevision, 1)
  assert.deepEqual(submitted.targetSceneIds, ['s_01'])
  assert.match(submitted.requestId, /^[0-9a-f-]{36}$/)
  assert.equal(workspace.task.value.revision, 2)
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
  await workspace.submit('revisions', { instruction: 'change', baseRevision: 1 })
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
