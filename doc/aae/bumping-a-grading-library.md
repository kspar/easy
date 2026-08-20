# Updating a grading library

For the people who maintain `tiivad`, `silmused` and the other graders. You need a GitHub account and
nothing else — no access to this repository, no server, no Docker.

## Bump a version

1. Open **[`doc/aae/pins/dev.yml`](pins/dev.yml)** on GitHub and press the pencil icon.
2. Change one number. Keep the quotes.

   ```diff
   -silmused.SILMUSED_VERSION: "1.7.11"
   +silmused.SILMUSED_VERSION: "1.7.12"
   ```

3. Press **Propose changes**, then **Create pull request**.

That is all. If everything passes, the pull request merges itself and dev picks up the new version
within a few minutes. Nobody has to approve it.

Change **one number in one file**. A pull request that touches anything else needs a developer to
look at it, which is the whole point of the rule — it is what makes merging without review safe.

## What happens after you press the button

Four checks run, in this order, and all four have to pass:

| Check | What it means if it fails |
| --- | --- |
| **Pins shape** | The change is not a simple version bump, or you are not listed for this library. |
| **Pins exist** | That version is not on PyPI. Usually a typo, occasionally a release that was yanked. |
| **Grading images** | The image was built with your version and **did not work**. This is the useful one — see below. |
| Backend / Web / Executor | The rest of the project's tests. These have nothing to do with your change; if one is red, tell a developer. |

`Grading images` builds the real image with your version in it and runs a check *inside* it. For
silmused that means starting its PostgreSQL and running a query through it; for tiivad it means
running a compiled exercise and confirming the grade. So a version that installs but does not work is
caught here, before it can grade anybody's submission.

If a check fails, read its output — it says what it found. Fix the number and push again to the same
pull request, or close it.

## Check it worked

Open **<https://dev.lahendus.ut.ee/about>** and look at the *Versions* block at the bottom:

```
executor-1     v4.0 (b14b916)      20/08/2026 09:40
  silmused       silmused 1.7.12     20/08/2026 09:52
  tiivad         tiivad 0.0.33       12/08/2026 11:03
```

The number next to each image is **what is actually installed in it**, read from the image itself —
not what the file says should be there. If those two ever disagree the row turns orange and shows
both, which means something is wrong and a developer should see it.

Give it about five minutes after the merge. Then grade something on dev to be sure.

## Undo it

Go to the merge commit on GitHub and press **Revert**. That opens a pull request putting the old
number back, it auto-merges under the same rules, and dev goes back within minutes.

It is quick on purpose: the old image is still on the server and still in the registry, so nothing is
rebuilt — the server just points at it again. Reverting is cheap, so do it rather than debugging a
broken version in place.

You can also just edit the number back by hand. Same thing.

## When something refuses

**"nobody is currently allowed to bump X on Y"** — you are not on the list for that library, or you
are trying to change production. Ask kspar to add you to
[`.github/pins-bumpers.yml`](../../.github/pins-bumpers.yml).

**"is not a plain dotted version"** — only digits and dots merge automatically. `1.7.12` is fine;
`1.7.12rc1`, `1.7.12.post1` and anything else needs a developer. Not because they are broken, but
because "should a grader run a release candidate" is a judgement, and judgements want a person.

**"X is not on PyPI"** — the check lists the versions that do exist. Usually a typo.

**"changes 2 files"** — split it into two pull requests, one per file.

**The image built but its check failed** — the version does not work in the image. The output says how
it failed. This is the check doing its job; nothing was deployed.

## Production

`doc/aae/pins/prod.yml` is the same file for production, and it is deliberately **not** something you
can merge yet: production is still updated by hand, so a merged change there would deploy nothing and
it would be reasonable to think it had. For now, bump dev, confirm it on the About page, and ask kspar
to promote it — they will move the exact image dev proved, not rebuild it.

## What you cannot break

- **You cannot deploy a version that does not work.** It is built and run before anything is
  published, and again on the server before it goes live.
- **You cannot lose the old version.** Published images are never overwritten, and the server keeps
  the last few.
- **You cannot break grading by merging.** If a new image fails its check on the server, the server
  puts the previous one back by itself and stops trying that version.

---

Full detail, including how it is built and what the server does: [`grading-images.md`](grading-images.md).
