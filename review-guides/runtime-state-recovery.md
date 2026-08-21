# Review Guide: runtime/ State and Recovery

Load this guide when a PR changes runtime state, checkpointing, serialization of
stored values, action-state handling, or async, mailbox, and recovery paths. It
narrows the full passes in `code_review.md` to the ones that matter most for this
area; the general passes still apply.

## Focused checklist

- Trace every stored value through its full serialize, checkpoint, and restore
  round-trip. Confirm the restored value keeps its original type instead of a
  lossy or re-encoded form, such as a `byte[]` that returns as a base64 `String`.
- Confirm a serde envelope or version tag scopes the value the way recovery
  needs it, not only the way the happy-path write needs it.
- Under the beta policy, question serde or state compatibility flags that only
  exist to read old formats. Prefer removing them unless they carry a concrete
  obligation.
- For a bugfix, check the failure actually stops reaching the old path after the
  fix, not just in the one code path the PR touched.
- Test the real runtime failure path through the operator, mailbox, or action
  task, not a helper in isolation. Make failure-injection fixtures deterministic
  so the regression test exercises the bug on every run.
- Cover async, retry, delayed-callback, and recovery paths when the change
  affects them.

## Examples from past reviews

| Case | Pass it exercises | Review |
|---|---|---|
| A versioned serde envelope for stored `MemoryUpdate` values carried an `isEnvelope` backward-compatibility flag. The review asked whether that compatibility path was essential or should be dropped under the beta policy. | Whether a serde compatibility channel must exist, or only keeps an old format alive. | [#874](https://github.com/apache/flink-agents/pull/874#discussion_r3541046130) |
| A hotfix propagated a swallowed action-task `Error` out of the mailbox instead of losing it in a `Future`. The review kept the linkage-error regression fixture deterministic, since `System.nanoTime()` may return a value that skips the injected failure, so the test exercises the real failure path on every run. | Testing the actual runtime failure path with a deterministic fixture. | [#880](https://github.com/apache/flink-agents/pull/880#discussion_r3540383941) |
