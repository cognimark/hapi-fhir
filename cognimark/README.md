# Cognimark HAPI core fork

Status, September 24, 2026: Core has deployed the long-ID source-built ARM64
runtime on the existing shared HAPI host after explicit schema widening.
Independent source readback then identified an XHTML lexical-preservation gap.
The parser change below is a candidate, not yet a deployed or accepted fix.
Product refresh activation remains a separate coordinated Core/Agent/KG gate.

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
The private database-partitioned storage path accepts resource IDs using
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

The model and storage artifacts remain `8.12.1-cognimark.1`. The new
`hapi-fhir-base` artifact is `8.12.1-cognimark.2`; other upstream dependencies
remain 8.12.1.
Do not publish modified binaries using the unmodified upstream coordinates.
The starter explicitly selects all three custom artifacts with dependency management.

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

## JSON XHTML source preservation

The upstream R4 XHTML model parses and re-encodes narrative strings. This can
convert numeric character references into literal characters, change quoting,
and rewrite empty-element syntax even when no application edits the narrative.
For example, `&#13;&#10;` becomes literal CR/LF. That violates Core's stricter
original-string comparison, even where the displayed narrative is unchanged.

`ParserOptions.setPreserveJsonXhtmlSource(true)` opts a context into lexical
preservation for JSON-parsed, unmodified XHTML values. The usual parser runs
first and malformed XHTML still fails there. Only after successful parsing is
the original string attached to that value together with its initial model
serialization. JSON encoding uses the original only while that serialization
is unchanged. A model edit invalidates the retained representation; newly
constructed narratives use the ordinary encoder. XML output is unchanged.
The option is off by default; the private starter enables it on managed FHIR
contexts before use. No hash normalization, extra database, retrieval fallback
or source-ID translation is introduced.

The marker is in-memory parser metadata. JSON storage retains the original
narrative itself, not a second stored body. A model-level deep copy that discards
user data does not inherit this lexical marker; HTTP transaction, history,
search and restart behavior must be qualified against the packaged service.
Existing rewritten historical strings cannot be reconstructed by installing
the new parser. Any repair must use retained original evidence, create a new
conditional version, preserve old history and explicitly reconcile its journal.

```sh
mvn -B -ntp -f hapi-fhir-base/pom.xml install
mvn -B -ntp -f cognimark/narrative-tests/pom.xml test
```

The synthetic baseline failed three of four narrative round-trip cases before
the change. The candidate passes all twelve focused checks, including model
edits, opt-out, malformed input and independent values in one bundle, plus all
564 base-module tests. The version-enum test recognizes the explicit private
distribution suffix while still checking the upstream compatibility enum.
These unit checks alone do not qualify persisted service behavior.
