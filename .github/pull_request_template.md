## What this changes

<!-- What a user or a maintainer would notice. One or two sentences is plenty. -->

## Why

<!-- Link the ADR or BDR if there is one. If this reopens a settled question, say
     so here and add a new record rather than editing the old one. -->

## The way in

Sprout's privacy claim is mostly a claim about *absence*, and absence is easy to
lose by accident ([ADR-0014](../docs/adr/0014-the-way-in-is-an-allow-list.md)).
Tick anything this PR touches — a tick is a prompt to say why in the description,
not a confession:

- [ ] A new **permission**, or a change to an existing one
- [ ] A component **exported**, or a new intent filter
- [ ] A new **`ContentProvider`** path, or a wider `@xml/file_paths`
- [ ] New code that reads a **file, stream or intent extra from outside the app**
- [ ] A change to what **crosses to a new phone** (`@xml/backup_rules`,
      `@xml/data_extraction_rules`)
- [ ] A new **dependency** (what does its manifest contribute?)
- [ ] None of the above

If any of the first four are ticked, `AttackSurfaceTest` and
`UntrustedInputFuzzTest` are where the new door gets written down and covered.

## Checks

- [ ] `CHANGELOG.md` updated under `## [Unreleased]`, in the voice of the
      existing entries — or the change is docs-only and doesn't belong there
- [ ] `PRIVACY.md` updated, if anything user-visible about data changed
- [ ] Room migration written and `app/schemas` committed, if the schema moved
