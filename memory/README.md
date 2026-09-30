# Shared memory

What we have learned while building Skill Atlas: decisions and the reasons for them,
traps we hit, and how we work. Any agent or person working on the repository reads
this before a task and updates it afterwards, using the `shared-memory` skill
(`.agents/skills/shared-memory/SKILL.md`).

The spec (`specs/spec.md`) says **what** the product does. Memory records **why** it is that
way, and what surprised us along the way. Don't copy the spec here. Link to its sections
instead.

| File | What goes in it |
|------|-----------------|
| [decisions.md](decisions.md) | Product and architecture decisions, with the reasons and what was rejected |
| [gotchas.md](gotchas.md) | Traps in the toolchain, the environment, and the libraries, and how to avoid them |
| [workflow.md](workflow.md) | How work gets done here: spec first, definition of done, PRs, parallel sandboxes |
| [preferences.md](preferences.md) | How the project owner likes to work and to be updated |

Entry format, newest last within each topic:

```markdown
## <short title> (YYYY-MM-DD)

<the fact, in one to three sentences>

**Why:** <the reason, or the incident that taught us>
**How to apply:** <what to do differently next time>
```
