# GitHub API recipes for pr-review

Everything here was verified against the live API schema. Where something is unverified it
says so.

## Contents

- [PR metadata](#pr-metadata)
- [Posting a review with inline comments](#posting-a-review-with-inline-comments)
- [File-level comments](#file-level-comments)
- [Prior rounds: threads, replies, unresolve](#prior-rounds-threads-replies-unresolve)
- [Submitting a verdict on its own](#submitting-a-verdict-on-its-own)
- [Failure modes](#failure-modes)

## PR metadata

```bash
gh pr view <n> --repo <owner/repo> \
  --json number,title,state,isDraft,author,headRefName,headRefOid,baseRefName,reviewDecision,mergeable
```

`headRefOid` is the `commit_id` every comment must be posted against. `author.login`
compared against `gh api user --jq .login` decides whether a verdict is even possible.

Existing reviews, to know whether this is round 1 or round 4:

```bash
gh pr view <n> --repo <owner/repo> \
  --jq '[.reviews[] | {author: .author.login, state, submittedAt}]' --json reviews
```

## Posting a review with inline comments

One call carries the comments and the event together. Build the payload in a script, never
inline: bodies contain backticks, apostrophes, `$`, and `->`, each of which breaks a heredoc
in its own way.

```python
import json

payload = {
    "commit_id": "<head sha>",
    "body": "<review body: verdict rationale, prior-round re-check, unanchorable findings>",
    "event": "COMMENT",  # or APPROVE / REQUEST_CHANGES
    "comments": [
        {
            "path": "src/main/kotlin/Foo.kt",
            "line": 84,
            "side": "RIGHT",
            "body": "IMPORTANT: ...",
        },
        {
            "path": "src/main/kotlin/Bar.kt",
            "line": 371,
            "side": "LEFT",
            "body": "MINOR: ...",
        },
    ],
}
json.dump(payload, open("/tmp/review.json", "w"))
```

```bash
gh api --method POST repos/<owner>/<repo>/pulls/<n>/reviews \
  --input /tmp/review.json \
  --jq '{id, state, html_url}'
```

**Ordering.** The `comments[]` order is the creation order, so IDs ascend with it and
`GET /pulls/{n}/comments` returns them in that order. It does not affect the Files changed tab,
which renders each comment at its anchor and is therefore always in diff order. Whether the
Conversation tab follows array order or diff order within a single review is **unverified** -
do not promise the author a severity-ordered reading surface; put the severity order in the
review body, which renders above the comments in both tabs.

**Updating a review body after posting - unverified.**
`PUT /repos/{owner}/{repo}/pulls/{pull_number}/reviews/{review_id}` with `{"body": "..."}` is
documented as the way to rewrite a submitted review's body, which is what a body index with
links to each comment needs. It has not been exercised from this skill; if it fails, keep the
plain `path:line` index rather than retrying.

Comment fields:

- `line` is the line number **in the file at the state that side represents** - the
  right-side (post-change) number for `side: RIGHT`, the original number for `side: LEFT`.
  Compute both with `scripts/diff_anchors.py`; do not count hunk lines by eye.
- `side` defaults to `RIGHT` when omitted. Set it explicitly anyway - a `LEFT` anchor posted
  without it silently lands on the wrong line.
- Multi-line comments take `start_line` + `start_side` alongside `line` + `side`.
- A suggestion is a fenced block inside `body`:

  ````
  ```suggestion
  	val tokens = contextTokens.coerceAtMost(ContextSizePolicy.MAX_CONTEXT_TOKENS)
  ```
  ````

  The block replaces exactly the commented line range, so the range must be the whole thing
  being replaced and the replacement must be complete. Match the file's indentation
  character - a suggestion with spaces in a tabs file is committable and wrong.

Verify what landed:

```bash
gh api "repos/<owner>/<repo>/pulls/<n>/comments?per_page=100" \
  --jq '[.[] | select(.pull_request_review_id==<id>) | {path, line: (.line // .original_line), side, first: (.body|split(":")[0])}]'
```

That `first` field is worth checking every time: it is the severity token, and it proves the
prefix rule held all the way through serialization.

## File-level comments

For a finding about a file as a whole (rung 3 of the anchor ladder).

**GraphQL - verified to exist.** `addPullRequestReviewThread` accepts
`subjectType: FILE` (the enum's values are `LINE` and `FILE`) and an optional
`pullRequestReviewId`, so the thread can attach to a review:

```bash
gh api graphql -f query='
mutation($prId: ID!, $path: String!, $body: String!) {
  addPullRequestReviewThread(input: {
    pullRequestId: $prId, path: $path, body: $body, subjectType: FILE
  }) { thread { id } }
}' -f prId="<PR node id>" -f path="src/Foo.kt" -f body="NITPICK: ..."
```

Get the PR node ID with:

```bash
gh api graphql -f query='{repository(owner:"<o>",name:"<r>"){pullRequest(number:<n>){id}}}' \
  --jq .data.repository.pullRequest.id
```

**REST - unverified.** The standalone create-review-comment endpoint documents
`subject_type`, but whether the bundled `POST /pulls/{n}/reviews` `comments[]` array accepts
it has not been confirmed. If a file-level comment is needed inside a bundled review, try it
once and fall back to the GraphQL mutation or to ladder rung 4 rather than assuming.

## Prior rounds: threads, replies, unresolve

Thread node IDs come from GraphQL. A REST comment `id` will not work for either mutation.

```bash
gh api graphql -f query='
{ repository(owner:"<o>", name:"<r>") { pullRequest(number:<n>) {
    reviewThreads(first: 100) { nodes {
      id
      isResolved
      isOutdated
      path
      line
      comments(first: 1) { nodes { author { login } body } }
    } } } } }' \
  --jq '.data.repository.pullRequest.reviewThreads.nodes[] |
        {id, isResolved, isOutdated, path, line, first: .comments.nodes[0].body[0:160]}'
```

Reply into a thread - verified input fields are `pullRequestReviewThreadId`, `body`,
and optionally `pullRequestReviewId`:

```bash
gh api graphql -f query='
mutation($threadId: ID!, $body: String!) {
  addPullRequestReviewThreadReply(input: {
    pullRequestReviewThreadId: $threadId, body: $body
  }) { comment { url } }
}' -f threadId="<thread node id>" -f body="IMPORTANT: still open at head - ..."
```

Unresolve a thread whose issue is still live - verified input field is `threadId`:

```bash
gh api graphql -f query='
mutation($threadId: ID!) {
  unresolveReviewThread(input: {threadId: $threadId}) {
    thread { id isResolved }
  }
}' -f threadId="<thread node id>"
```

Reply first, then unresolve. A thread that pops back open with no explanation reads as noise;
one that pops open under a reply reads as a finding.

## Submitting a verdict on its own

The second call of the confirmation gate: same endpoint, no `comments` array.

```bash
gh pr review <n> --repo <owner/repo> --approve --body-file /tmp/verdict.md
gh pr review <n> --repo <owner/repo> --request-changes --body-file /tmp/verdict.md
```

`--body-file` avoids the quoting problem entirely. Confirm it landed:

```bash
gh pr view <n> --repo <owner/repo> --json reviewDecision,reviews \
  --jq '{decision: .reviewDecision, approvals: [.reviews[] | select(.state=="APPROVED") | .author.login]}'
```

`reviewDecision` can come back empty even after a successful approval - that means the repo
requires a review from a specific team or `CODEOWNERS` entry that this approval does not
satisfy. Report that rather than claiming the PR is green.

## Failure modes

| Symptom | Cause |
|---|---|
| 422 on the reviews POST | A comment's `line` is not in the diff for that `side`, or `commit_id` is not the head SHA. Re-run `diff_anchors.py --check` on every comment. |
| 422 "Can not approve your own pull request" | PR author is the authenticated user. Degrade to `COMMENT`. |
| Comment lands on the wrong line | `side` omitted on a `LEFT` anchor, or the line was counted by eye instead of computed. |
| `bash: eval: unexpected EOF` / `bad substitution` | JSON or a body written inline in a heredoc. Use a file. |
| Suggestion the author commits and it breaks the build | Suggestion covered a partial range, or used spaces in a tabs file. |
| Thread mutation returns "Could not resolve to a node" | A REST comment ID was passed where a GraphQL thread node ID is required. |
