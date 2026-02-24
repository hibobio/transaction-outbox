---
name: pr-comments-replies
description: Adds or replies to pull request review comments using the GitHub API. Use when the user asks to comment on a PR, reply to PR comments, address review feedback, or respond to unresolved comments on a pull request.
---

# PR Comments and Replies

How to add comments and reply to review comments on GitHub pull requests using `gh api` (or GitHub MCP when available).

## When to Use

- User asks to "reply to PR comments", "address review feedback", or "respond to unresolved comments"
- User asks to "add a comment" or "comment on the PR"
- Replying to Copilot or other review comments in bulk

## Resolving Comments

**Ask the user:** Before replying to PR comments, ask: "Should I also mark these comments as resolved after replying?"

- **Default: do not mark as resolved.** Only mark comments as resolved if the user explicitly says yes.
- If the user wants to resolve: use GitHub GraphQL `resolveReviewThread` (review thread ID, not comment ID) or the GitHub UI; the REST API does not support resolving comment threads.

## List PR Review Comments

To get comment IDs and bodies for a PR (e.g. to reply to unresolved comments):

```bash
gh api "repos/{owner}/{repo}/pulls/{pull_number}/comments" \
  --jq '.[] | "\(.id)|\(.path)|\(.line // .original_line)|\(.body[0:120])"'
```

Example with a real repo:

```bash
gh api "repos/hibobio/play-commons/pulls/599/comments" \
  --jq '.[] | "\(.id)|\(.path)|\(.line // .original_line)|\(.body[0:120])"'
```

Filter by author if needed (e.g. Copilot): `--jq '.[] | select(.user.login == "copilot-pull-request-reviewer") | ...'`

## Reply to a Review Comment

**Endpoint:** `POST /repos/{owner}/{repo}/pulls/{pull_number}/comments/{comment_id}/replies`

Replies must target a **top-level** review comment (not a reply). Replies to replies are not supported.

```bash
gh api "repos/{owner}/{repo}/pulls/{pull_number}/comments/{comment_id}/replies" \
  -X POST \
  -f body='Your reply text here.'
```

- `comment_id`: integer ID from the list endpoint (use the ID as-is in the path).
- `body`: the reply text (form field or JSON `"body": "..."`).

Example:

```bash
gh api repos/hibobio/play-commons/pulls/599/comments/2841509966/replies \
  -X POST \
  -f body='Addressed by having the test override use GenericPublisherProvider.'
```

## Create a New Review Comment (on a line)

To add a **new** comment on a specific line (not a reply), use:

```bash
gh api "repos/{owner}/{repo}/pulls/{pull_number}/comments" \
  -X POST \
  -f body='Comment text' \
  -f commit_id="$(gh api repos/{owner}/{repo}/pulls/{pull_number} --jq '.head.sha')" \
  -f path='path/from/repo/root/file.scala' \
  -f line=42 \
  -f side=RIGHT
```

- `commit_id`: HEAD commit of the PR branch (e.g. from `pulls/{pull_number}` `head.sha`).
- `path`: file path in the repo.
- `line`: line number in the file on the given side (LEFT or RIGHT), not the diff position.
- `side`: `LEFT` (old) or `RIGHT` (new).

## Workflow: Reply to All Unresolved Comments

1. **Ask:** "Should I also mark these comments as resolved after replying?" If the user does not answer or says no, do not mark as resolved (default).
2. List comments: `gh api "repos/{owner}/{repo}/pulls/{pull_number}/comments"` (optionally filter by author with `--jq`).
3. For each comment to reply to, call: `gh api "repos/{owner}/{repo}/pulls/{pull_number}/comments/{comment_id}/replies" -X POST -f body='...'`.
4. Use concise, factual reply bodies (e.g. "Done.", "Fixed in commit X.", "Addressed by ...").
5. Only if the user said yes to resolving: mark threads as resolved. The REST API does not support resolving; use GraphQL `resolveReviewThread` (review thread ID) or the GitHub UI. Otherwise skip.
6. Optionally add `sleep 1` between replies when handling many comments.

## Notes

- **Resolving:** Always ask whether to mark comments as resolved. Default is **do not** mark as resolved. Resolving is not supported by REST; use GraphQL or the GitHub UI.
- **Reply endpoint:** Use `POST .../pulls/{pull_number}/comments/{comment_id}/replies` for replies (pull_number is required in the path per GitHub REST API). The general "create review comment" endpoint does not accept `in_reply_to`; it expects either reply params or new-comment params (commit_id, path, line, etc.).
- **IDs:** Comment IDs are integers; use them in the URL path.
- **GitHub MCP:** If available, prefer GitHub MCP tools for commenting; otherwise use `gh api` as above.
- **Rate limiting:** Use `sleep 1` between replies when handling many comments.
