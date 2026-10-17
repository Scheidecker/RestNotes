# RestNotes commit history
Author: Carlos Scheidecker

## Snapshot and scope
- Repository: [Scheidecker/RestNotes](https://github.com/Scheidecker/RestNotes).
- Snapshot date: 2026-10-17 UTC.
- HEAD: `c43ae10b17f088abe48e0008c46dedd22c8f270a` on `main`.
- Commits: **12**, newest first in topological order, including all commits reachable from the locally available branches, remote-tracking branches and tags (`git log --all`).
- This checkout is not shallow. No remote fetch was performed; commits not yet fetched or reachable only through reflogs/deleted references are outside this snapshot.
- Git records the author and committer as `antunes` for every commit below. The document's author attribution does not change that Git metadata.
- Author and committer timestamps are identical for each listed commit and retain their original `-06:00` offset.

The full original commit messages are reproduced below. This is a historical snapshot, not an automatically updated changelog; it excludes the uncommitted creation of this document.

## 1. c43ae10 — Architecture documentation and diagrams
- Commit: [c43ae10b17f088abe48e0008c46dedd22c8f270a](https://github.com/Scheidecker/RestNotes/commit/c43ae10b17f088abe48e0008c46dedd22c8f270a)
- Git author/committer: `antunes`.
- Authored/committed: `2026-10-16T18:23:10-06:00`.
- Parent: `cec09aa3fc918420edd62794c2f8270b8710dfe7`.
- References at snapshot: `HEAD -> main`, `origin/main`.

**Original commit message**
> Created PlantUML diagrams and their correspondent SVGs for the MarkDown documentation. New architecture, features and requirements docs. Updated documents.

## 2. cec09aa — Documentation updates
- Commit: [cec09aa3fc918420edd62794c2f8270b8710dfe7](https://github.com/Scheidecker/RestNotes/commit/cec09aa3fc918420edd62794c2f8270b8710dfe7)
- Git author/committer: `antunes`.
- Authored/committed: `2026-10-16T17:52:38-06:00`.
- Parent: `9de98413ed86fea73fdaccf0e044c8914324b466`.

**Original commit message**
> Documentation updates.

## 3. 9de9841 — Optional editing leases
- Commit: [9de98413ed86fea73fdaccf0e044c8914324b466](https://github.com/Scheidecker/RestNotes/commit/9de98413ed86fea73fdaccf0e044c8914324b466)
- Git author/committer: `antunes`.
- Authored/committed: `2026-10-16T17:44:55-06:00`.
- Parent: `d07223e6920f9f0dc28c0b354bcf2036c1869b2c`.

**Original commit message**
> Implemented optional five-minute editing leases.

## 4. d07223e — Tag management
- Commit: [d07223e6920f9f0dc28c0b354bcf2036c1869b2c](https://github.com/Scheidecker/RestNotes/commit/d07223e6920f9f0dc28c0b354bcf2036c1869b2c)
- Git author/committer: `antunes`.
- Authored/committed: `2026-10-16T17:23:02-06:00`.
- Parent: `34173e899a80eed6ea9a59a294df74786f08aa21`.

**Original commit message**
> Implemented tag management. Updated documentation and Notes logic.

## 5. 34173e8 — Note CRUD and atomic history/activity
- Commit: [34173e899a80eed6ea9a59a294df74786f08aa21](https://github.com/Scheidecker/RestNotes/commit/34173e899a80eed6ea9a59a294df74786f08aa21)
- Git author/committer: `antunes`.
- Authored/committed: `2026-10-16T16:58:54-06:00`.
- Parent: `f070f529292c2e1791edbaaf1a2cda568fb76b75`.

**Original commit message**
> Implemented all six note CRUD endpoints. Create, paginate notes. Retrieve MD notes.Real changes record history and activity atomically. Docs update.

## 6. f070f52 — Notebooks and cross-team sharing
- Commit: [f070f529292c2e1791edbaaf1a2cda568fb76b75](https://github.com/Scheidecker/RestNotes/commit/f070f529292c2e1791edbaaf1a2cda568fb76b75)
- Git author/committer: `antunes`.
- Authored/committed: `2026-10-16T16:19:56-06:00`.
- Parent: `175e7be45a9414ddc4bc1f6e27e3e370d269a527`.

**Original commit message**
> Implemented notebooks and cross-team sharing. Database changes, documentation changes.

## 7. 175e7be — Team administration
- Commit: [175e7be45a9414ddc4bc1f6e27e3e370d269a527](https://github.com/Scheidecker/RestNotes/commit/175e7be45a9414ddc4bc1f6e27e3e370d269a527)
- Git author/committer: `antunes`.
- Authored/committed: `2026-10-16T15:43:03-06:00`.
- Parent: `eb0b52280e136127af4a8a3e430f4a975f20f29f`.

**Original commit message**
> Implemented team administration. Updated documentation.

## 8. eb0b522 — Authentication and security
- Commit: [eb0b52280e136127af4a8a3e430f4a975f20f29f](https://github.com/Scheidecker/RestNotes/commit/eb0b52280e136127af4a8a3e430f4a975f20f29f)
- Git author/committer: `antunes`.
- Authored/committed: `2026-10-16T15:20:26-06:00`.
- Parent: `99f2fd2542edc35a7aea8f566250c9e07750f76f`.

**Original commit message**
> Implemented the authentication/security layer. More documentation. More tests.

## 9. 99f2fd2 — MySQL persistence, migrations and demo data
- Commit: [99f2fd2542edc35a7aea8f566250c9e07750f76f](https://github.com/Scheidecker/RestNotes/commit/99f2fd2542edc35a7aea8f566250c9e07750f76f)
- Git author/committer: `antunes`.
- Authored/committed: `2026-10-16T15:00:56-06:00`.
- Parent: `58c51d50720679be9ed18af1c87367d905bc751d`.

**Original commit message**
> Implemented:  MySQL 8.4.11, Liquibase 5.0.4 migrations, and JPA entities/repositories.  Two isolated teams—Engineering and Marketing—each with its own admin, notebook, sample note, initial history, and activity.  Database constraints, tenant-aware pagination, optimistic versions, and production credential/demo-data safeguards.  Updated README and usage guides, plus docs/Database-Schema-Reference.md.

## 10. 58c51d5 — YAML and test changes
- Commit: [58c51d50720679be9ed18af1c87367d905bc751d](https://github.com/Scheidecker/RestNotes/commit/58c51d50720679be9ed18af1c87367d905bc751d)
- Git author/committer: `antunes`.
- Authored/committed: `2026-10-16T14:39:28-06:00`.
- Parent: `c9483628e96a2d69934220ca0ab6fcdc04a219ec`.

**Original commit message**
> Changes on yaml and tests.

## 11. c948362 — Initial Spring Boot application
- Commit: [c9483628e96a2d69934220ca0ab6fcdc04a219ec](https://github.com/Scheidecker/RestNotes/commit/c9483628e96a2d69934220ca0ab6fcdc04a219ec)
- Git author/committer: `antunes`.
- Authored/committed: `2026-10-16T14:37:10-06:00`.
- Parent: `e76c11c09f60821bb40e8ddc99ed00bd6f5ba3e7`.

**Original commit message**
> Initial application with basic design SpringBoot pieces and documentation including actuator part.

## 12. e76c11c — Initial commit
- Commit: [e76c11c09f60821bb40e8ddc99ed00bd6f5ba3e7](https://github.com/Scheidecker/RestNotes/commit/e76c11c09f60821bb40e8ddc99ed00bd6f5ba3e7)
- Git author/committer: `antunes`.
- Authored/committed: `2026-10-16T11:47:38-06:00`.
- Parent: none (root commit).

**Original commit message**
> Initial commit

## Inspecting subsequent history
From the repository root or its API subdirectory, use:

```bash
git --no-pager log --all --topo-order --format=fuller --date=iso-strict
```

Refresh this document when new commits are added. Branch labels are recorded only as they appeared at this snapshot and may move afterward.
