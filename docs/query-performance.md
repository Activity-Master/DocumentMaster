# Document query performance

Resource creation, type-link creation and private resource security are owned by
`IResourceItemService.createBinaryScopeRestricted`. Immutable values are inserted
through `storeResourceDataValue` and retrieved through `getResourceDataValue`.
Document Master performs its domain authorization first and keeps these service
calls in the same stateless transaction as the version and metadata changes.
Neither initial uploads nor revisions create a deprecated `ResourceItemData` row.

The PostgreSQL audit on 2026-10-01 exercised all Document Master service operations
and captured 64 distinct native query shapes, including default/deep pagination,
individual/combined metadata filters, historical reads, mutation predicates and
lock statements. The captured parameters and page sizes were used for replay;
the plans are from actual service SQL, rather than separately handwritten queries.

## Measurements

The isolated `postgres:17-alpine` fixture contains 12,000 additional documents,
96,000 version markers, approximately 804,000 document classifications, 1,200
additional buckets and 96,000 retained membership rows. Eight contiguous metadata
versions share one immutable binary per document. Each of the two main buckets
contains 3,000 documents; other documents are distributed across the other buckets.
There is also the smaller functional-test dataset and the audit's own operation
history. The workload uses an authorized Work actor.

Read execution times below are PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)` medians of
five samples, in milliseconds. Planning time is reported separately. These are
local fixture measurements, not production latency or HTTP throughput guarantees.

| Query | Original execution | Final execution | Original planning | Final planning |
|---|---:|---:|---:|---:|
| Document list, first page | 215.994 | 29.721 | 114.394 | 9.513 |
| Document list, offset 2,000 | 201.419 | 30.116 | 93.061 | 10.224 |
| Category filter | 232.133 | 42.510 | 94.227 | 10.183 |
| Label filter | 256.356 | 42.311 | 99.181 | 10.582 |
| Literal title substring | 250.246 | 44.331 | 101.655 | 10.288 |
| Category, label and title together | 249.050 | 45.433 | 98.403 | 11.624 |
| Bucket list | 26.248 | 14.547 | 80.488 | 23.083 |
| Child bucket list | 25.757 | 16.115 | 82.828 | 24.717 |
| Current properties for 50 documents | 3.232 | 2.917 | 0.688 | 0.463 |

[The complete 64-query summary](query-performance.csv) records the other queries,
selected indexes and sequential scans. Query IDs correspond to the generated
`.sql` and `.plan` files. DML rows have execution time zero because they were
planned without execution; zero does not mean that a write costs nothing.
Operations themselves execute through the real service, including publishing,
restore, ratings, grants, revocation, linking, hierarchy and archive. Their single
observed wall times are kept separately in `baseline/operations.tsv`; they are
not before/after medians and include framework/session/transaction overhead.

## Query changes

- Listing materializes authorization for the requested bucket once within the
  same SQL statement, then expands its contents. Membership, role, context,
  enterprise, system and effective-date checks still apply to that statement.
- Resource type validation uses an existence check, avoiding duplicate resource
  rows and the resulting `DISTINCT`. The `OFFSET 0` inside that point lookup is
  intentional: it prevents decorrelation into an enterprise-wide type scan.
- ActiveFlag authorization uses a scalar lookup by its primary key. This keeps
  repeated flag joins out of the planner's join search. Missing flags still deny
  access and flag state is queried on every statement.
- Boolean permission probes stop after one match. Exact-cardinality checks for
  taxonomy, current versions and rating summaries remain intact.
- Bulk property/rating projection groups rows by target once in Java. Current
  categories and labels are sorted in Java, removing the large SQL value sort;
  historical output uses the same ordering.

The audit caught and rejected an intermediate rewrite that improved planning but
repeated bucket authorization for every candidate resource. The final materialized
bucket query removes that regression.

## Index selection

The append-only migration is
`core/src/main/resources/db/23.document-query-indexes.sql`, registered at the end
of `FsdmSchema.orderedScripts()`. It creates two non-unique indexes:

| Index | Keys | Purpose |
|---|---|---|
| `idx_document_member_bucket_actor_to` | arrangement, party, enterprise, system, effective end | Current membership/access, member enumeration and membership expiration |
| `idx_document_bucket_contents_to` | enterprise, system, arrangement, effective end, resource | Current bucket contents and containment checks |

Both include the classification, effective start and ActiveFlag ID; the membership
index also includes its short access value. Dates precede the classification so
current rows can be found before joining to the role definition. This matters when
most memberships are expired: the final bucket-list query fell from 107.415 ms
without these indexes to 14.547 ms with them on the same dataset.

The contents index is selected by the final list and containment plans. The list's
largest gain comes from the query rewrite; its execution is approximately 30 ms
with or without the new indexes on this fixture. The index also avoids expanding
all historical links when a bucket's contents are resolved.

Candidate indexes for actor-first membership and resource creation ordering were
not selected by the measured plans and were removed. A candidate current-metadata
index did not improve bulk projection and was also removed. Existing primary-key,
relationship and `ric_item_class_eff_idx` indexes cover payloads, current/versioned
properties, metadata filters, party ownership/ratings and hierarchy traversal.
History metadata and binary reads were below one millisecond of database execution
in this fixture. Sequential scans of small reference tables were retained.

The original/new query comparison was collected while testing the candidates;
the final plans select only the two indexes retained in the migration. The unused
candidates are excluded from the delivered script.

Resource row locks continue to serialize revisions and rating changes. Bucket
locks protect membership and containment mutations. Hierarchy changes retain the
system-row guard that prevents cycles involving concurrent changes to different
parents. Read-only operations do not add row locks.

The migration uses ordinary `CREATE INDEX IF NOT EXISTS` inside the tracked schema
update transaction. Existing installations receive it through the canonical update
runner; large installations should account for the index build's write lock.

## Reproduction and validation

From the workspace root, with Docker available:

```powershell
mvn -f ActivityMaster/documents/pom.xml test `
  '-Dtest=DocumentQueryPerformanceTest' `
  '-Ddocument.performance=true' `
  '-Ddocument.performance.label=review' `
  '-Dmaven.test.failure.ignore=false' `
  '-Dcheckstyle.skip' '-Dmaven.javadoc.skip=true'
```

The opt-in test drops only these candidate indexes in its own disposable database,
loads the canonical rows, measures the existing indexes, applies the migration and
measures again on the same data. It writes SQL, plans and TSV summaries to
`target/document-performance-review/{baseline,indexed}`. Inserts/updates are
explained without `ANALYZE`, preserving the workload's already committed history.
No host database is used. Ordinary test runs skip this larger audit.

The combined PostgreSQL, REST, GraphQL, versioning and audit run passed 35 tests.
The behavior tests include immutable value-ID collision/rollback, concurrent stale
writers, current access after revocation, archive retention, stable ratings,
contiguous SCD boundaries and migration-compatible legacy reads.
