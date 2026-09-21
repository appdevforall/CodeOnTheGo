---
name: pr-review
description: Review a GitHub pull request and post every finding as an inline PR comment prefixed with a severity (CRITICAL / IMPORTANT / MINOR / NITPICK), then approve, comment, or request changes per the verdict rules. Wraps the built-in /code-review and owns the posting. Use this whenever the user wants a PR reviewed, wants findings or feedback posted onto a PR, asks whether previously flagged issues were fixed, asks whether a PR should be approved or blocked, or asks for review comments with severities - even if they never say "inline" or name a severity level. Prefer this over plain /code-review whenever the target is a pull request, because /code-review alone posts unlabelled standalone comments and never records a verdict. Use it too when the PR is one layer of a stack of dependent PRs, where a finding has to be checked against the stack tip rather than against the layer's own diff.
---

# pr-review

Review a PR, post findings as inline comments carrying a severity, and record a verdict.

This skill does not do the reviewing. `/code-review` does that, and it is good at it.
This skill owns what happens to the findings afterwards: grading them, proving each one
can be anchored, writing them as comments an author can triage by prefix, and turning the
set into an approve / comment / request-changes decision that is defensible.

Everything below exists because a review that posts a public verdict under the user's
GitHub identity has a higher bar than one that prints to a terminal. A wrong line number
is a comment attached to unrelated code. A wrong severity trains the author to ignore the
labels. A wrong verdict either blocks a colleague or clears a bug into main.

## Invocation

```
/pr-review [low|medium|high|max] [--verdict] [--even-if-approved] [<pr#>|<branch>]
```

- Effort defaults to `high`. A cheap pass is fine when findings go to a terminal; it is
  not fine when the output is a public review, so do not silently drop below `high`.
- `--verdict` pre-authorizes submitting `APPROVE` or `REQUEST_CHANGES` without stopping to ask.
- `--even-if-approved` overrides the stop in step 2 and reviews a PR that already has an
  approval.

## Step 1: resolve the target

With an explicit PR number or URL, use it. With a branch or nothing, ask
`gh pr view --json number,author,headRefOid,url` for the current branch.

- **An open PR exists** - review it. Full flow.
- **No PR** - run the review anyway, print severity-prefixed findings to the terminal, and
  say plainly that nothing was posted and no verdict was computed. Do not invent a PR to
  post to, and do not stay quiet about the degradation: the user asked for a review that
  posts, and needs to know it did not.

Record the head SHA now. Every anchor and the review POST have to refer to that one commit,
or a comment lands on code that has moved. A stacked PR verifies its claims against a
different commit - the next section says which, and why the two come apart.

### Stacked PRs

A stacked PR is based on another PR's branch instead of the trunk, so its diff shows one layer
and its files contain every layer below. Detect this before anything else reads a file, because
every later step changes.

```bash
gh repo view --json defaultBranchRef --jq '.defaultBranchRef.name'   # the trunk
gh stack view --json                                                  # the whole stack, if gh-stack is installed
```

`gh stack view` without `--json` opens a TUI that hangs a non-interactive session, so always pass
it. Without the extension, walk the chain through the API - down toward the trunk by following
`baseRefName`, up away from it by asking which open PR is based on this one:

```bash
gh pr list --state open --head "<baseRefName>" --json number,headRefName,headRefOid,baseRefName
gh pr list --state open --base "<headRefName>" --json number,headRefName,headRefOid,baseRefName
```

Repeat each until it returns empty. A PR based on the trunk with nothing based on it is not
stacked; skip the rest of this section.

Otherwise record **two** SHAs, because they answer different questions:

- **The review head** - this PR's `headRefOid`. Anchors and the review POST use it, because the
  comments belong to the PR being reviewed.
- **The stack tip** - the `headRefOid` of the topmost PR. Every *claim* is checked against it.

The tip is the verification ref because a stack merges atomically - `gh stack merge` is
all-or-nothing across the whole chain - so the tip is the state that actually reaches the trunk,
and it is the state a finding has to be wrong in to be worth an author's time. Checking against
the layer's own head instead re-reports defects that a higher layer already repaired, and a
reviewer who does that twice stops being read.

Name the stack in the review body: the layer's position, the PRs below and above it, and the tip
SHA that verification used. A reader who does not know a finding was graded against code outside
this PR cannot check the grading.

## Step 2: stop if the PR is already approved

Ask GitHub for the PR's approvals before doing anything else with it. (With no PR at all -
the second branch of step 1 - there is nothing to have been approved, so skip to step 3.)

```bash
gh api "repos/<owner>/<repo>/pulls/<n>/reviews" --paginate \
  --jq '[.[] | select(.state == "APPROVED")
         | {user: .user.login, sha: .commit_id, at: .submitted_at, url: .html_url}]'
```

**If that array is non-empty, stop.** Do not read the repo's rules, do not run
`/code-review`, do not post anything. Say plainly that the PR is already approved, name each
approver, when they approved, and the approval's URL, and end there.

It does not matter whether the approver is the user or a colleague. An approval means the
change already cleared somebody's bar, and the author has been told they are done. Reviewing
anyway spends a full `/code-review` pass to land a second, competing verdict on top of that -
and a `REQUEST_CHANGES` over a colleague's approval is a decision the user should take
deliberately, not one this skill should take on their behalf. Naming the approver is what lets
them take it: an approval from a bot and an approval from the tech lead read very differently,
and the user can see which they have.

Two details decide this correctly:

- **Filter the REST reviews list on `state == "APPROVED"`, not `gh pr view --json
  reviewDecision`.** `reviewDecision` comes back empty on a repo with no required reviewers even
  when someone has approved, so keying on it misses exactly the approvals worth stopping for.
  A dismissed approval carries `state: "DISMISSED"`, so the same filter drops it, which is
  right - a dismissed approval is not an approval.
- **Do not reach for `gh pr view --json latestReviews` to get the SHA or the link.** It returns
  the review states correctly but leaves `commit.oid`, `id`, and `url` as empty strings, so
  staleness computed from it is silently always "current".

**A stale approval still stops the run, but say that it is stale.** When an approval's
`commit_id` is not the head SHA from step 1, commits have landed since someone signed off.
Report the approved SHA, the current head, and how many commits separate them:

```bash
gh api "repos/<owner>/<repo>/compare/<approved-sha>...<head-sha>" --jq '.ahead_by'
```

Then offer to review anyway.
An approval of code that no longer exists is worth reporting as an approval and worth not
treating as current, and only the user knows which of those two matters for this PR.

To review an approved PR regardless, the user passes `--even-if-approved` or says so in the
request ("re-review it even though Daniel approved"). Then carry on through the rest of the
flow, and name the existing approval in the review body so the author can see why a second
review appeared over one they had already cleared.

## Step 3: read the repo's review rules

Before grading anything, look for rules that outrank the defaults in this file:
`CLAUDE.md`, `REVIEW.md`, `CONTRIBUTING.md`, `.github/PULL_REQUEST_TEMPLATE*`, and any
`.claude/skills/*review*`. Read what you find.

Repos disagree about what blocks a merge, and the disagreement is the point. One repo may
tie a QA transition to "no critical, high, or medium findings", which is stricter than the
table below; another may have no written rule at all. Apply what the repo says and name the
document in the review body. When there is nothing, say so - "this repo has no written
approve/request-changes rule, so the default applied" is useful to a reader and stops the
next person assuming a rule exists.

## Step 4: run the review

Invoke `/code-review` at the resolved level with the PR target, and **without `--comment`**.

`--comment` would make the built-in post the findings itself: one standalone comment per
finding, no severity prefix, no verdict, on an endpoint that cannot carry an approval. This
skill posts instead, so let the built-in do only the finding.

The review may run inline or as a background fork depending on the session. Either way its
output reaches you as prose, not as structured data - findings it reports through
`ReportFindings` go to the host UI, not to you. Do not try to force a machine-readable
handoff. Step 5 re-derives what you need from the diff, which you have to do anyway.

## Step 5: verify every finding against the diff

Treat the review's output as a **lead list**, not as a result. For each finding, fetch the
diff once and check the claim yourself:

```bash
gh pr diff <n> --repo <owner/repo> > /tmp/pr.diff
python .claude/skills/pr-review/scripts/diff_anchors.py /tmp/pr.diff --check <path>:<line>
```

The script prints the source text at that anchor if GitHub will accept a comment there, and
fails if it will not. Use `--file <name>` to dump a file's postable anchors when a finding's
line is off by a few, and `--json` when scripting over many findings.

Three things to confirm per finding, because each has been wrong in practice:

1. **The line still says what the finding claims.** Line numbers cited in prose drift - a
   finding once cited "line 43" for a KDoc that was at 44 and whose real subject was at 90.
2. **The anchor is postable.** A line outside the hunks is rejected by the API, and a
   finding about pre-existing code often points there. Go to the ladder in step 8.
3. **The claim survives reading the code.** Re-derive the arithmetic, follow the callers,
   check the sibling call site. A finding you cannot reproduce is downgraded to PLAUSIBLE or
   dropped - never posted at full confidence because the review sounded sure.

In a stack, all three checks read the file **at the stack tip** - `git show <tip>:<path>`, or
`gh api "repos/<owner>/<repo>/contents/<path>?ref=<tip>"`. Not the working tree, which is
whatever branch happens to be checked out; not the trunk, which is missing every layer the PR
depends on and turns each of those dependencies into a false finding. Two dispositions follow
from grading against the tip, and both are decisions about where a finding is useful, not about
whether it is true:

- **A defect a higher layer already fixes is not posted.** It cannot reach the trunk, so a
  comment about it costs the author a triage and buys nothing. Drop it and name it in the
  closing report - which PR fixes it, and where - so the fact that this layer does not stand
  alone is visible to the one person who can act on it.
- **A defect whose real site is in a lower layer is still posted here**, because the author is
  working this layer now. Anchor it by the ladder in step 8 and open the first sentence with the
  true location and the PR that owns it: "the defect is at `Foo.kt:412`, introduced by #1801
  below this PR in the stack".

Mark each surviving finding CONFIRMED (you reproduced it) or PLAUSIBLE (it reads as real but
you could not prove it). That distinction feeds both severity and the verdict.

## Step 6: assign severity

You assign it, not the review. Severity is a claim about consequence, and it has to be
consistent with the verdict you are about to submit - so one head owns both.

Grade on a single axis: **what happens if this ships as-is.**

- **CRITICAL** - wrong on the shipped path. Crash, ANR, data loss, a security hole, or the
  feature does not do what it claims. Reachable by ordinary use with no unusual input.
- **IMPORTANT** - right on the common path, wrong on a path a real user or device will hit:
  a boundary value, an error branch, a slow or missing volume, an empty collection, a second
  invocation, a rotation. You can name the input and the wrong result.
- **MINOR** - a real defect no current caller can reach, or a hole in the change's own safety
  net: a latent overflow behind a clamped caller, a fix with no test pinning it, docs or a PR
  description that will mislead QA. Safe to merge; leaving it makes the next change riskier.
- **NITPICK** - no defect. Dead code, a pass-through indirection, a misplaced comment, a name.

Two rules that keep the scale honest:

- **A PLAUSIBLE finding is capped one level down.** An unverified CRITICAL is how a review
  cries wolf, and an author who overrides one label starts overriding all of them.
- **The line between IMPORTANT and MINOR is reachability by any current caller**, and a MINOR
  comment has to say why it is unreachable today. That sentence is what makes the grade
  checkable instead of asserted - "both in-repo callers pass 4096 or a value capped at 16384"
  lets the author verify the reasoning rather than trust it.

Do not discount a finding's severity because an earlier round already raised it. Repetition
is not evidence of unimportance.

## Step 7: re-check the previous rounds

Default on whenever the PR already has review comments. This is often the highest-value part
of the review: a fix that regressed, or was never made, is worth more than a new nitpick.

```bash
gh api "repos/<owner>/<repo>/pulls/<n>/comments?per_page=100" \
  --jq '.[] | {id, path, line: (.line // .original_line), user: .user.login, body: .body[0:200]}'
```

For each prior finding, decide fixed / not fixed / partly fixed, and:

- **Never mark it fixed on the strength of a reply.** "Fixed in abc123" is a claim by the
  author about their own work. The check is reading the code at head - re-derive the
  arithmetic, confirm the guard covers all three call sites, diff the committed binary if the
  fix only ships through one.
- **A prior finding still open becomes a finding in this round** at its own severity, and it
  goes as a **reply in the existing thread**, not a new one. A reviewer who opens a second
  thread for the same issue is how findings get lost.
- **Unresolve the thread** if it was resolved while the issue is still live.

Thread replies and unresolving both need the GraphQL thread node ID, not a REST comment ID.
See `references/github-api.md` for the query and the two mutations.

Summarize the re-check in the review body, one line per prior finding with the evidence.
It is not anchorable, and it is what tells a reader the round was more than a fresh skim.

## Step 8: anchor each finding

Try in order, and stop at the first rung that carries the finding honestly:

1. **Exact line, `RIGHT`.** The default.
2. **Exact line, `LEFT`** - when the finding is about something the PR *deleted*. Underused
   and it works: a stale PR description that still references a removed API anchors best on
   the removed API's own line.
3. **File-level thread** (`subjectType: FILE` via GraphQL) - when the finding is about the
   file as a whole, such as a wrapper that should not exist.
4. **Nearest line in the diff the finding genuinely concerns**, with the true location named
   in the first sentence: "the defect is at `Foo.kt:412`, outside this diff". In a stack, name
   the PR that owns the line too - a layer's diff is thin, so this rung and the next carry more
   of the review than they would on a standalone PR.
5. **The review body**, still severity-prefixed, under an explicit "findings without a diff
   anchor" heading.

Two rules: a finding is **never dropped** for want of an anchor, and the body **never
restates** an anchored finding. Say it once, where it lives, so the author reads each thing
exactly one time. The severity index in step 11 is the one thing that is not a restatement -
one clause per finding, no failure trace and no fix - and it earns its place because the view
the author reads cannot be severity-ordered on its own.

## Step 9: write the comments

Each comment begins with one of exactly four uppercase tokens, then a colon. Nothing before
it - no bold, no bracket, no lead-in - because the prefix is what the author greps and sorts
by, and `**CRITICAL:**` starts with an asterisk.

```
IMPORTANT: <one sentence naming the defect, not the fix>

<the concrete failure: inputs or state -> wrong result. For MINOR and NITPICK,
where there is no runtime failure, say instead who gets misled and what future
change this breaks.>

<the fix, named concretely, in one sentence.>
```

- **One finding per comment.** No bundling a nit into a MINOR - the author triages by
  severity, and a buried finding is either inflated or invisible.
- **About 120 words.** CRITICAL and IMPORTANT may run longer when the failure needs a trace.
  Past that, a comment stops being read.
- **A `suggestion` block only when the fix is a single within-hunk replacement that fully
  resolves the finding.** GitHub lets the author commit a suggestion in one click, and a
  half-fix committed in one click is worse than no suggestion at all.
- No headers, no decorative structure, at most one short list.

## Step 10: cap the volume

- 15 inline comments total, at most 5 NITPICK.
- **The cap only ever sheds NITPICKs**, keeping the ones that touch code the PR actually
  added. A CRITICAL, IMPORTANT, or MINOR is never dropped to fit.
- If CRITICAL + IMPORTANT + MINOR alone exceed 15, post all of them and say in the body that
  the change is too large to review in one pass. That is a finding about the PR's size.
- Dropped nits get one body line naming them, not silence.

A review with 25 comments, 12 of them nits, is worse than one with 8 - the labels stop doing
their job when the volume drowns them.

## Step 11: post

One `POST /repos/{owner}/{repo}/pulls/{n}/reviews` carrying `commit_id` (the head SHA from
step 1), the body, `event`, and the whole `comments[]` array. One call, one review, atomic.

Build the payload as JSON with a script and send it with `gh api --input`. Do not hand-write
JSON inline in a shell command: review bodies contain backticks, quotes, `$`, and `->`, and
every one of them breaks a heredoc differently.

### Order the payload by severity

Sort `comments[]` before sending: CRITICAL, then IMPORTANT, then MINOR, then NITPICK, and
within a severity by path and line so the order is deterministic rather than incidental.

Be clear-eyed about what that buys, because it is easy to over-claim. It does **not** reorder
the Files changed tab: an inline comment renders at the line it is anchored to, so that view is
always in diff order and no payload can change it. What it does buy is that comments are created
in array order, so their IDs ascend by severity - which is the order `GET /pulls/{n}/comments`
returns them in, and therefore the order the step 7 re-check walks them in next round. A
deterministic order also makes two runs over the same PR diffable.

Since the surface the author reads cannot be severity-ordered, the body carries that ordering
instead. Open it with an index, highest severity first:

```
CRITICAL
- EditorActivity.kt:412 - decoder reused after the activity is recreated

IMPORTANT
- FileManager.kt:88 - rotation drops the pending edit
- Content.kt:203 - an empty selection throws

MINOR
- BuildConfig.kt:31 - overflow unreachable behind both current callers

NITPICK - 2 inline, not listed
```

One clause per finding, no failure trace and no fix - the anchored comment already carries
those, and the index exists to give the author a triage order and the only view where the whole
review is visible at once. Nitpicks get a count rather than lines, because listing them at the
top is exactly the volume problem step 10 is trying to avoid.

To make the entries clickable, the comment IDs do not exist until the POST returns. Post once,
then rewrite only the body with `PUT /repos/{owner}/{repo}/pulls/{n}/reviews/{review_id}`,
substituting each entry for the `html_url` the response gave it. That is optional - a plain
`path:line` index is greppable and costs no second call - and the endpoint is documented but not
exercised here, so treat a failure as expected and keep the plain index.

Re-read the head SHA immediately before posting and compare it against step 1. A stack makes
this more than paranoia: `gh stack rebase` and `gh stack sync` rewrite every branch above the
one that changed, so a teammate touching a lower layer moves this PR's head while the review is
being written. A `commit_id` that no longer exists fails the POST outright, which is the good
case; the bad case is anchors that still resolve and now point at shifted lines. If it moved,
re-run step 5's anchor checks against the new head before posting.

Exact payload shape and the GraphQL alternatives: `references/github-api.md`.

## Step 12: the verdict

| Confirmed findings | Event |
|---|---|
| Any CRITICAL or IMPORTANT | `REQUEST_CHANGES` |
| Any MINOR, nothing above it | `COMMENT` |
| Only PLAUSIBLE findings, any severity | `COMMENT`, saying what could not be verified |
| NITPICK only, or none | `APPROVE` |

MINOR does not block, on purpose: the definition says *safe to merge*, and submitting a
formal merge block for a class of finding defined as safe to merge burns the one signal that
should mean "do not ship this". PLAUSIBLE does not block either - a review that blocks on
unconfirmed suspicion teaches authors to override it.

Three things override the table:

- **The repo's own rule from step 3**, when it has one. Name it.
- **A self-authored PR.** GitHub refuses `APPROVE` and `REQUEST_CHANGES` on your own PR;
  compare the PR author against the authenticated user and degrade to `COMMENT` with a
  one-line reason instead of firing a call that 422s.
- **Non-interactive without `--verdict`** - degrade to `COMMENT` and say the verdict was
  computed but not submitted.

### The confirmation gate

Post the inline comments **immediately**, as one review with event `COMMENT`. The findings
are the deliverable and should not wait on a human.

Then:

- **Computed verdict is `COMMENT`** - done. One call, nothing to ask.
- **Computed verdict is `APPROVE` or `REQUEST_CHANGES`** - state it with the reasoning and
  ask, unless `--verdict` was passed. On confirmation, submit a second, comment-free review
  carrying just that event and a short body naming what the author should do.

This costs two entries in the PR's review history and buys two things: findings that land
even if the user steps away, and a consequential action under their identity that they chose.

## Report back

Close with what the user cannot see from the terminal: the review URL, the count by
severity, the verdict submitted (or computed and withheld, and why), and which repo document
governed the verdict. For a stacked PR, add the stack's shape, the tip SHA claims were verified
against, and every finding dropped as already-fixed-higher-up with the PR that fixes it - that
list is the answer to "does this layer stand alone", which the posted review deliberately does
not carry. If any finding was dropped, degraded, or could not be anchored, say
which and why - a review is also a claim about its own coverage.

Jira and other tracker transitions are out of scope. Mention one only if the repo's rules
tie a status to a review outcome, and then only as an offer.
