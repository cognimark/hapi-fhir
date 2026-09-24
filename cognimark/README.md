# Cognimark HAPI core fork

Status, September 24, 2026: development baseline established; source changes,
packaged-runtime qualification and production activation are pending.

## Baseline and ownership

- Upstream: `hapifhir/hapi-fhir`, tag `v8.12.0`.
- Exact upstream commit: `7ab4c7c7c49281496faa1b3c2498a652457669ae`.
- Development branch: `cognimark/8.12.0-long-resource-ids`.
- Service packaging: the existing
  [Cognimark starter fork](https://github.com/cognimark/hapi-fhir-jpaserver-starter/tree/cognimark/8.12.0-long-resource-ids).
- Infrastructure, schema rollout, ingest contracts and source admission remain
  owned by Core. Do not put environment configuration, credentials or clinical
  fixtures in this public repository.

This repository owns actual HAPI library source changes. The starter consumes
the qualified library build; Core consumes its immutable container image. Core
must not maintain a second copy of the HAPI source patch. Preserve upstream
history and keep the customization reviewable against the exact release tag.

## Approved compatibility target

Preserve upstream resource IDs and references without identity translation.
The private database-partitioned storage path will accept resource IDs using
`[A-Za-z0-9.-]` with a maximum length of 512. This is an intentional private
storage extension, not a claim that IDs above 64 conform to the FHIR standard.
Do not broaden version IDs, ETags, the global FHIR validator or the ID alphabet.

The bounded source changes belong to storage ID admission and the resource-ID
column model. Rebuild all consumers of compile-time constants, including the
patient-unique-identifier entity. Existing database schema changes must be
explicitly deployed and verified separately; automatic DDL is not a migration
or rollback strategy. Legacy non-database-partitioned identity storage is not
qualified by this design.

## Release gates

1. Add regression tests and demonstrate their failure against the baseline.
2. Apply the source changes and build version-pinned artifacts with Maven.
3. Package the changed libraries into the starter artifact. Do not inject loose
   replacement classes through a runtime classpath override.
4. Qualify 64/65/88/256/512-character resource identities and rejection above
   the limit, without weakening strict FHIR validation.
5. Qualify transaction receipts, references, tenant isolation, conditional
   writes, history, deletion, restart and populated-schema migration.
6. Complete Core's independent retained-input readback and exact-version KG
   delivery tests before activating product refresh. Fork creation, a successful
   compile or an image push alone does not satisfy these gates.

No upstream pull request is part of this internal customization. This fork
setup and its documentation were prepared with Codex assistance.
