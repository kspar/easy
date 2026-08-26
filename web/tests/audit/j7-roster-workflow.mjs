/**
 * Unit J7 — roster & groups: the workflow questions the five existing specs do not ask.
 *
 *  A. Getting students in. Email invites were dropped on purpose (EZ-1740), so the invite link is the
 *     way. From a fresh course: how many clicks to something shareable, and does the UI say what the
 *     link *does* (expiry, uses)?
 *  B. Removing a student. Destructive; what does the confirm say will be destroyed — specifically,
 *     does it mention their submissions?
 *  C. Deleting a group that still has members — the groups spec proves the request fan-out; the audit
 *     question is what the warning tells the teacher.
 *
 * Reuses tests/support/participants-groups-fixtures.mjs; moodle_linked: false so nothing is gated.
 *
 *   HARNESS_PORT=5299 node tests/audit/j7-roster-workflow.mjs
 */
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { withBrowser, fakeApi, shoot, REPORTS, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { COURSE, GROUPS, participants, baseStubs } from '../support/participants-groups-fixtures.mjs'

const P = { ...participants, moodle_linked: false, students_moodle_pending: [] }
const deleteCalls = []

await withBrowser(async ({ launch }) => {
  const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
  const inviteCalls = []
  let created = null // the invite the PUT made; GETs must return it or the UI can never show the link
  await fakeApi(
    page,
    [
      // Invite creation must precede baseStubs' empty-invite stub — and must be STATEFUL: the first
      // run answered every GET with "no invite", so after the PUT the refetch said the link still did
      // not exist and the UI never displayed it. The audit then read "no copy button, no expiry
      // shown" off a state its own stub had made impossible.
      [new RegExp(`/courses/${COURSE}/invite(\\?|$)`), ({ method, body, route }) => {
        inviteCalls.push({ method, body })
        if (method === 'PUT' || method === 'POST') {
          created = { invite_id: 'AB12CD', expires_at: body?.expires_at ?? null, allowed_uses: body?.allowed_uses ?? null, used_count: 0, created_at: '2026-08-24T20:00:00.000Z' }
          return created
        }
        if (method === 'DELETE') { created = null; return {} }
        if (created) return created
        return route.fulfill({ status: 200, contentType: 'application/json', body: '' })
      }],
      [new RegExp(`/courses/${COURSE}/students(\\?|$)`), ({ method, body }) => {
        if (method === 'DELETE') deleteCalls.push(body)
        return { removed_active_count: 1, removed_pending_count: 0 }
      }],
      ...baseStubs(),
      [new RegExp(`/courses/${COURSE}/participants(\\?|$)`), () => P],
      [/\/v2\//, () => ({ courses: [], exercises: [], count: 0 })],
    ],
    { log: false, contract: false },
  )

  await page.goto(`${BASE_URL}/courses/${COURSE}/participants`)
  await waitUntil(async () => (await page.getByText('Maasikas').count()) > 0, { timeout: 15000 })
  await page.waitForTimeout(1000)
  await shoot(page, 'j7-01-roster')

  // ── A. the invite path ───────────────────────────────────────────────────────────────────────────
  let clicks = 0
  const click = async (loc, what) => { clicks++; await loc.click(); console.log(`   click ${clicks}: ${what}`) }
  const inviteBtn = page.getByRole('button', { name: /kutse|invite|Lisa|liitumis/i }).first()
  const inviteBtnLabel = (await inviteBtn.count()) ? (await inviteBtn.innerText()).trim() : null
  console.log(`[A] first invite-ish button: ${JSON.stringify(inviteBtnLabel)}`)
  if (inviteBtnLabel) {
    await click(inviteBtn, inviteBtnLabel)
    await page.waitForTimeout(1000)
    const dlg = await page.evaluate(() => {
      const d = document.querySelector('.MuiDialog-root') ?? document.querySelector('main')
      const t = (d?.innerText ?? '').replace(/\s+/g, ' ')
      return {
        text: t.slice(0, 450),
        mentionsExpiry: /aegu|kehtiv|expires|päev/i.test(t),
        mentionsUses: /kasutuskord|uses|korda/i.test(t),
        hasCopy: [...(d?.querySelectorAll('button') ?? [])].some((b) => /kopeeri|copy/i.test(b.innerText || b.getAttribute('aria-label') || '')),
      }
    })
    console.log(`[A] after opening: expiry mentioned=${dlg.mentionsExpiry}, uses mentioned=${dlg.mentionsUses}, copy button=${dlg.hasCopy}`)
    console.log(`[A] invite calls so far: ${JSON.stringify(inviteCalls)}`)
    console.log(`[A] text: ${JSON.stringify(dlg.text.slice(0, 320))}`)
    await shoot(page, 'j7-02-invite')
    await page.keyboard.press('Escape')
    await page.waitForTimeout(500)
  }

  // ── B. removing a student ────────────────────────────────────────────────────────────────────────
  const row = page.getByText('Maasikas').first()
  await row.click() // select? or open? observe
  await page.waitForTimeout(600)
  // Try the row checkbox route the roster spec uses.
  const checkboxes = page.locator('main input[type=checkbox]')
  if (await checkboxes.count()) {
    await checkboxes.nth(1).check().catch(() => {})
    await page.waitForTimeout(500)
  }
  // List EVERY affordance the selection reveals — the first run grabbed the first /eemalda/ match,
  // which was "Eemalda rühmast" (remove from group), and audited the wrong action.
  const affordances = await page.evaluate(() =>
    [...document.querySelectorAll('main button')]
      .filter((b) => b.offsetParent !== null)
      .map((b) => (b.innerText || b.getAttribute('aria-label') || '').trim())
      .filter((t) => t && /eemalda|remove|kustuta/i.test(t)),
  )
  console.log(`\n[B] destructive affordances after selecting a student: ${JSON.stringify(affordances)}`)
  const removeBtn = page.getByRole('button', { name: /Eemalda kursuselt|kursuselt/i }).first()
  const removeLabel = (await removeBtn.count())
    ? (await removeBtn.innerText() || await removeBtn.getAttribute('aria-label'))
    : affordances.find((a) => !/rühmast/i.test(a)) ?? null
  console.log(`[B] remove-from-course affordance: ${JSON.stringify(removeLabel)}`)
  if (removeLabel) {
    await removeBtn.click()
    await page.waitForTimeout(800)
    const confirm = await page.evaluate(() => {
      const d = document.querySelector('.MuiDialog-root')
      const t = (d?.innerText ?? '').replace(/\s+/g, ' ')
      return {
        present: !!d,
        text: t.slice(0, 400),
        namesStudent: /Maasikas/.test(t),
        mentionsSubmissions: /esitus|lahendus|submission|hinne|grade/i.test(t),
      }
    })
    console.log(`[B] confirm: present=${confirm.present}, names the student=${confirm.namesStudent}, mentions submissions/grades=${confirm.mentionsSubmissions}`)
    console.log(`[B] text: ${JSON.stringify(confirm.text.slice(0, 300))}`)
    await shoot(page, 'j7-03-remove-confirm')
    await page.keyboard.press('Escape')
  }

  // ── C. deleting a non-empty group ───────────────────────────────────────────────────────────────
  const groupsTab = page.getByRole('tab', { name: /rühm|group/i }).first()
  if (await groupsTab.count()) {
    await groupsTab.click()
    await page.waitForTimeout(1000)
    await shoot(page, 'j7-04-groups-tab')
    // Select Rühm A (has members) and find delete.
    const gRow = page.getByText('Rühm A').first()
    if (await gRow.count()) {
      const cb = page.locator('main input[type=checkbox]')
      if (await cb.count()) await cb.nth(1).check().catch(() => {})
      await page.waitForTimeout(400)
      const del = page.getByRole('button', { name: /kustuta|delete/i }).first()
      if (await del.count()) {
        await del.click()
        await page.waitForTimeout(800)
        const warn = await page.evaluate(() => {
          const d = document.querySelector('.MuiDialog-root')
          const t = (d?.innerText ?? '').replace(/\s+/g, ' ')
          return { present: !!d, text: t.slice(0, 350), mentionsMembers: /liige|liiget|member|õpilas/i.test(t) }
        })
        console.log(`\n[C] delete-group dialog: present=${warn.present}, mentions members=${warn.mentionsMembers}`)
        console.log(`[C] text: ${JSON.stringify(warn.text.slice(0, 280))}`)
        await shoot(page, 'j7-05-delete-group')
      } else console.log(`\n[C] no delete button found after selecting a group`)
    }
  }

  writeFileSync(join(REPORTS, 'j7-roster-workflow.json'), JSON.stringify({ inviteCalls, deleteCalls }, null, 2))
  await page.close()
})
console.log('\nreport written')
