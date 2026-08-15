/**
 * Uploading a file from the Markdown editor: the toolbar picker, paste, and what each inserts.
 *
 * The interesting behaviour is not "does it call the endpoint" but **what Markdown comes out**, and
 * that is decided by the *sniffed* type the server returns rather than by the file's extension — so
 * the stub here deliberately answers with a type that contradicts the name, which is the case a
 * client that guessed locally would get wrong.
 *
 * The placeholder logic itself is unit-covered in `tests/unit/markdown-actions.test.mjs` against
 * raw CodeMirror state. What this checks is the wiring: that the gestures reach it at all.
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const ID = '7'

const article = {
  id: ID,
  title: 'Frequently asked questions',
  created_at: '2026-01-01T10:00:00.000Z',
  last_modified: '2026-08-01T12:00:00.000Z',
  owner: { id: 'kspar', given_name: 'Kaspar', family_name: 'Papli' },
  author: { id: 'kspar', given_name: 'Kaspar', family_name: 'Papli' },
  text_html: '<p>Hello</p>',
  text_md: 'Hello',
  published: true,
  aliases: [{ id: 'faq', created_at: '2026-01-01T10:00:00.000Z', created_by: 'kspar' }],
}

test('markdown-upload', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'md-upload-' })

  /** What the next POST /v2/files will answer, and what it was actually sent. */
  let nextUpload = { id: 'AAAAAAAAAAAAAAAAAAAAAAAAAAA', filename: 'shot.png', mime_type: 'image/png' }
  let uploadStatus = 200
  let uploadError = null
  const uploadedBodies = []

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    ['/teacher/courses', () => ({ courses: [] })],
    ['/preview/markdown', ({ body }) => ({ content: `<p>PREVIEW:${body.content}</p>` })],
    [
      '/files',
      async ({ route }) => {
        // postDataJSON() throws on multipart, so fakeApi leaves `body` undefined here — the raw text
        // is the only way to see what was sent, and it is enough to prove the part name and filename.
        uploadedBodies.push(route.request().postData() ?? '')
        if (uploadStatus !== 200) {
          await route.fulfill({
            status: uploadStatus,
            contentType: 'application/json',
            body: JSON.stringify(uploadError ?? {}),
          })
          return
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(nextUpload),
        })
      },
    ],
    ['/articles/7', () => article],
    ['/articles/faq', () => article],
  ])

  await page.addInitScript(() => localStorage.setItem('activeRole', 'admin'))
  await page.goto(`${BASE_URL}/a/faq`)
  await page.getByRole('button', { name: 'Edit' }).click()

  const editor = page.locator('.cm-content').first()
  const docText = () => editor.innerText()
  await waitUntil(async () => (await docText()).includes('Hello'))

  // --- the toolbar picker ---------------------------------------------------------------------------

  // The image button now opens a menu rather than acting immediately, because uploading did not
  // replace pasting a URL you already have — an externally hosted image is ordinary Markdown.
  await page.getByRole('button', { name: 'Image' }).click()
  check('the image button offers both routes',
    await page.getByRole('menuitem', { name: /Upload/ }).isVisible() &&
    await page.getByRole('menuitem', { name: /URL/ }).isVisible())
  await shot('01-image-menu')

  await page.getByRole('menuitem', { name: /Upload/ }).click()
  await page.setInputFiles('input[type=file]', {
    name: 'shot.png', mimeType: 'image/png', buffer: Buffer.from('fake-png-bytes'),
  })

  check('the file reaches the endpoint', await waitUntil(() => uploadedBodies.length === 1))
  check('as a part named file', (uploadedBodies[0] ?? '').includes('name="file"'))
  check('with the filename', (uploadedBodies[0] ?? '').includes('shot.png'))
  check('an image becomes image markdown', await waitUntil(async () =>
    (await docText()).includes('![shot.png](/v2/resource/AAAAAAAAAAAAAAAAAAAAAAAAAAA/shot.png)')))
  check('and the placeholder is gone', !(await docText()).includes('uploading-'))

  // --- the sniffed type decides, not the extension ----------------------------------------------------

  nextUpload = { id: 'BBBBBBBBBBBBBBBBBBBBBBBBBBB', filename: 'handout.png', mime_type: 'application/pdf' }
  await page.getByRole('button', { name: 'Image' }).click()
  await page.getByRole('menuitem', { name: /Upload/ }).click()
  await page.setInputFiles('input[type=file]', {
    name: 'handout.png', mimeType: 'image/png', buffer: Buffer.from('%PDF-1.4 not really a png'),
  })
  // Named .png and offered as image/png by the browser, but the server sniffed a PDF. A client that
  // trusted the extension would emit `![...]` and render a broken image.
  check('a sniffed non-image becomes a link', await waitUntil(async () =>
    (await docText()).includes('[handout.png](/v2/resource/BBBBBBBBBBBBBBBBBBBBBBBBBBB/handout.png)')))
  check('and not an image', !(await docText()).includes('![handout.png]'))

  // --- paste ------------------------------------------------------------------------------------------

  nextUpload = { id: 'CCCCCCCCCCCCCCCCCCCCCCCCCCC', filename: 'pasted.png', mime_type: 'image/png' }
  await editor.click()
  await page.evaluate(() => {
    const dt = new DataTransfer()
    dt.items.add(new File(['bytes'], 'pasted.png', { type: 'image/png' }))
    document.querySelector('.cm-content')
      .dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }))
  })
  check('pasting a file uploads it', await waitUntil(async () =>
    (await docText()).includes('![pasted.png](/v2/resource/CCCCCCCCCCCCCCCCCCCCCCCCCCC/pasted.png)')))

  // Pasting text must still paste text. Claiming every paste event would break the thing people do a
  // thousand times more often than attaching a screenshot.
  const beforeTextPaste = uploadedBodies.length
  await page.evaluate(() => {
    const dt = new DataTransfer()
    dt.setData('text/plain', 'JUST TEXT')
    document.querySelector('.cm-content')
      .dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }))
  })
  check('pasting text uploads nothing', uploadedBodies.length === beforeTextPaste)

  // --- a failed upload --------------------------------------------------------------------------------

  uploadStatus = 413 // the reverse proxy refusing the body before core ever sees it
  const beforeFail = await docText()
  await page.getByRole('button', { name: 'Image' }).click()
  await page.getByRole('menuitem', { name: /Upload/ }).click()
  await page.setInputFiles('input[type=file]', {
    name: 'huge.png', mimeType: 'image/png', buffer: Buffer.from('x'),
  })
  check('an over-size upload says so specifically', await waitUntil(() =>
    page.getByText(/too large/i).first().isVisible()))
  check('and leaves no placeholder behind', await waitUntil(async () =>
    !(await docText()).includes('uploading-')))
  check('and does not insert a broken link', await waitUntil(async () =>
    !(await docText()).includes('huge.png')), beforeFail)
  await shot('02-upload-error')

  // --- by URL still works -------------------------------------------------------------------------------

  uploadStatus = 200
  const beforeByUrl = uploadedBodies.length
  await page.getByRole('button', { name: 'Image' }).click()
  await page.getByRole('menuitem', { name: /URL/ }).click()
  check('by-URL inserts the old placeholder markup', await waitUntil(async () =>
    (await docText()).includes('![')  && (await docText()).includes('https://')))
  check('and uploads nothing', uploadedBodies.length === beforeByUrl)

  await close()
})
