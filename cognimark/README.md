# Cognimark HAPI core fork

Status, October 9, 2026: Core has deployed the source-built ARM64 `.5` runtime
with long-ID storage, JSON XHTML preservation, heartbeat lifecycle repair and
single-owner native-work recovery and existing-reference prefetch. The `.6`
incoming-reference prefetch change is under runtime/performance qualification.
Core owns deployment and ongoing full-rebuild
acceptance; a library version or unit-test pass is not a production-status claim.

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

The model artifact remains `8.12.1-cognimark.1`. The
`hapi-fhir-base` artifact is `8.12.1-cognimark.2`. The scheduler (`hapi-fhir-jpa`)
and Batch2 artifacts use `8.12.1-cognimark.3`. The in-development
storage, search-parameter, Batch2-job and `hapi-fhir-jpaserver-base` artifacts
use `8.12.1-cognimark.6`; other upstream
dependencies remain 8.12.1.
Do not publish modified binaries using the unmodified upstream coordinates.
The starter must explicitly select each qualified custom artifact with dependency management.
The focused CI workflow uses the same digest-pinned Maven 3.9.12 / Java 17
container as the starter and image build. Do not depend on the hosted runner's
ambient Maven version: newer model validation rejects duplicate plugin entries
in the upstream JPA POM before the selected tests can execute.

## Transaction-local reference prefetch

The deployed `.5` release preloads existing reference target identities alongside the
bounded resource/index batch, before per-resource changes accumulate in Hibernate.
This replaces repeated identity queries and automatic-flush scans with batched
lookups using the same native ID helper. Reindex v2 and v3 pass their transaction
details explicitly. The existing two-argument storage API remains available for
upstream callers that do not participate in transaction-local reference prefetch.

The cache uses persistent ID **and partition**, contains positive results only,
is cleared on rollback, and dies with the transaction. It does not populate all
resolved-reference entries in advance: each resource activates only its own
existing references. New or unresolved targets still use ordinary validation,
resolution and placeholder creation. No shared cache, persistent schema, index
definition, flush mode, optimistic-lock setting or job parameter changes.

The same release fixes an off-by-one error in existing-link matching:
`Patient/abc` must compare `abc`, not `/abc`, to the reference's ID part. Type,
path, case and version checks remain in place. Tests cover those mismatches,
absolute references and IDs through 512 characters.

The `.6` release extends the bounded reindex prefetch to incoming relative
reference targets, using the native collection identity resolver and the exact
source partition. Bodies are parsed once and consumed at their usual per-resource
reindex boundary; parse failures are reported there rather than aborting healthy
resources. A corrected version/history or a write invalidates the parsed body.
Updates/deletes invalidate prefetched target identities, and rollback clears all
new state. Positive lookups still pass ordinary link validation. Missing or
deleted targets, conditional/remote references, cross-partition hooks and
placeholder creation keep their native paths. No target is prematurely inserted
into the native resolved-ID cache.

Existing-link matching builds a lazy per-resource index once, reuses the batch's
partition-aware identity map, and preserves path/type/ID/case/version matching.
It is not a persistent or process-wide cache. Reindex concurrency and chunk
parameters are unchanged. HTTP/PostgreSQL, restart and paired performance
qualification are owned by Core and must not be inferred from unit results.

Core accepted `.6` as production `core-hapi:8` on October 9 at 15:48 UTC, after
source-pinned AMD64 and ARM64 TLS/PostgreSQL/recovery qualification. Original
native jobs continued and authorized FHIR/KG reads matched. Core's
`docs/hapi-reindex.md` records paired synthetic results and live acceptance;
runtime deployment does not imply completion of the ongoing full BSC rebuild.

```sh
mvn -B -ntp -f hapi-fhir-jpaserver-searchparam/pom.xml install
mvn -B -ntp -f hapi-fhir-storage/pom.xml -Dtest=BaseStorageDaoResourceIdTest,DaoResourceLinkResolverTest install
mvn -B -ntp -f hapi-fhir-storage-batch2-jobs/pom.xml install
mvn -B -ntp -f hapi-fhir-jpaserver-base/pom.xml \
  -Dtest=LocalBatch2WorkRecoveryTest,ReindexPartitionContextTest,JpaJobPersistenceImplTest,ReindexReferencePrefetchTest install
mvn -B -ntp -f cognimark/reindex-tests/pom.xml test
```

## Batch2 heartbeat lifecycle

The scheduler may assign a default group during job registration. Batch2 must
capture its cancellation key **after** registration; otherwise completed chunks
keep a trigger in the actual group while cancellation targets Quartz's `DEFAULT`
group. The `cognimark.3` patch resolves the key after registration. It does not
disable active heartbeats, alter chunk recovery, or change stored clinical data.
Already leaked in-memory triggers require a process restart; persisted jobs and
checkpoints remain the source of recovery. Do not edit Batch2 rows to clear them.

Per-job registration details (interval and cron) are DEBUG, not INFO. Scheduler
lifecycle, errors and job progress keep their existing levels. Heartbeat execution
does not log SQL or a per-tick message at the normal production log level.

```sh
mvn -B -ntp -f hapi-fhir-jpa/pom.xml install
mvn -B -ntp -f hapi-fhir-storage-batch2/pom.xml install
mvn -B -ntp -f cognimark/batch2-tests/pom.xml test
```

The focused suite runs the real Spring/Quartz scheduler against packaged JARs.
Override `-Dcognimark.hapi.batch.version=8.12.1` to reproduce six failing checks
against the official baseline. All eight checks pass with the patch: explicit
and default groups, success and exception cleanup, repeated cancellation without
affecting another chunk, absent chunks, active ticks stopping after close, and
DEBUG-only registration. The scheduler module's six tests and Batch2's 215 tests
also pass. The original small restart test covered a completed discovery gate,
not a populated local queue. Production showed that already QUEUED notices are
lost with the in-memory broker; the expanded Core regression must also interrupt
queued and executing work before claiming general restart recovery.

## Local work recovery and partition-aware reindex

`LocalBatch2WorkRecovery` is an explicit single-owner deployment facility, not
automatic cluster failover. Invoke it once before native scheduling starts and
only with the local `LinkedBlockingBrokerClient`, after the previous database
owner has exited. It restores unfinished pre-boot work in the job's current
step to READY; native maintenance owns subsequent dispatch and bounded queue
backpressure. Completed chunks, payloads, retries, parameters, future gates,
cancelled/failed jobs and current-boot work are not reset. No periodic recovery
poller or second queue is introduced. Durable brokers own their own redelivery
and are rejected by this facility. This source change still requires packaged
restart qualification before production use.

Search-parameter reindex now carries the persisted source partition in its
system request, including writes of missing reference placeholders. It does
not select a default tenant when the resource has an explicit partition, loosen
partition isolation, or change clinical JSON and history.

Focused verification runs 15 persistence/recovery checks, including the four
partition-context cases. The standalone partition suite fails all four cases
against official 8.12.1 (missing request partition) and passes all four against
the candidate. Core's first packaged runtime check also passes interruption
with queued/executing work, same-job continuation, tenant-local placeholder
creation, working reference search, and exact historical reads. These local
checks are not production rollout acceptance.

Core accepted the source-pinned `.4` runtime replacement on October 9, 2026,
after full AMD64 and emulated ARM64 runtime checks. Production startup restored
21,457 unfinished chunks, preserving the original 28 BSC jobs and sampled
completed checkpoints. Full-population reindex completion and the separate
historical-error repair remain Core acceptance tasks, not claims of these unit
tests. Core's `docs/hapi-reindex.md` owns the deployment and measurement receipts.

```sh
mvn -B -ntp -f hapi-fhir-jpaserver-base/pom.xml \
  -Dtest=LocalBatch2WorkRecoveryTest,ReindexPartitionContextTest,JpaJobPersistenceImplTest install
mvn -B -ntp -f cognimark/reindex-tests/pom.xml test
```

The original four partition-context tests were also run against official
8.12.1 before extending the suite with the `.5` storage API contract.

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
These unit checks alone do not qualify persisted service behavior. The packaged
runtime additionally passes Core's actual PostgreSQL/HAPI TLS upgrade, conditional
update, transaction, search and restart test. Its complete Core ingest/refresh
regression passes 1,535 checks with eight unrelated opt-in skips. Native ARM64
acceptance verifies lexical preservation, tenant isolation, decimal precision
and stale-write rejection. These are bounded runtime/input qualifications, not
a claim of passing every upstream test or activating the product refresh route.
