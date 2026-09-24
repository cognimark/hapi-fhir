# Cognimark HAPI core fork

Status, September 24, 2026: the 8.12.1 storage customization and focused Maven
tests are implemented. Application-image qualification, coordinated schema
rollout and production activation remain pending.

## Baseline and ownership

- Upstream: `hapifhir/hapi-fhir`, tag `v8.12.1`, the latest stable release
  verified on September 24, 2026.
- Exact upstream commit: `c8cc1dbbb2568610b68de626ea00c21080e0b8f8`.
- Development branch: `cognimark/8.12.1-long-resource-ids`.
- Service packaging: the existing
  [Cognimark starter fork](https://github.com/cognimark/hapi-fhir-jpaserver-starter/tree/cognimark/8.12.1-long-resource-ids).
- Infrastructure, schema rollout, ingest contracts and source admission remain
  owned by Core. Do not put environment configuration, credentials or clinical
  fixtures in this public repository.

This repository owns actual HAPI library source changes. The starter consumes
the qualified library build; Core consumes its immutable container image. Core
must not maintain a second copy of the HAPI source patch. Preserve upstream
history and keep the customization reviewable against the exact release tag.
The earlier 8.12.0 planning branch is superseded. The historical 8.4/8.10
Cognimark runtime is not a source baseline for this work.

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

## Build and focused verification

Only `hapi-fhir-jpaserver-model` and `hapi-fhir-storage` are custom artifacts,
versioned `8.12.1-cognimark.1`. Their other upstream dependencies remain 8.12.1.
Do not publish modified binaries using the unmodified upstream coordinates.
The starter explicitly selects both custom artifacts with dependency management.

From a clean checkout, using Java 17 and Maven:

```sh
mvn -B -ntp -pl hapi-fhir-jpaserver-model,hapi-fhir-storage \
  -Dtest=ResourceTableTest,BaseStorageDaoResourceIdTest \
  -Dsurefire.failIfNoSpecifiedTests=false install
```

The baseline failed the new column-bound assertion and seven storage-admission
cases before the source change. All 21 selected tests then passed, including
the actual entity annotations, boundary rejection and unchanged strict
primitive validation. Maven packaging and duplicate-class checks also passed.
This is not a claim that every upstream test or the production runtime passed.
