# Agent instructions

Skills live in `skills/<name>/SKILL.md`. Read the matching one and follow it automatically when its trigger applies. The user does not need to ask.

| Skill | Use it when |
|---|---|
| `writing-plans` | Before any multi-step or non-trivial change, before touching code. Plans go in `docs/superpowers/plans/`. |
| `diagnosing-bugs` | Something is broken, crashing, failing or slow, or the user says "debug" or "diagnose". Build a pass/fail feedback loop first. |
| `frontend-design` | Building or changing UI, screens, layouts, colours or typography. Avoid generic, templated looks. |
| `find-skills` | A task needs a capability no skill here covers. Search skills.sh (`npx skills find`) instead of guessing. |
| `supabase-postgres-best-practices` | Anything touching a Postgres or Supabase database: schema, queries, migrations, RLS. |
