# Document Master

Document Master is an independently discovered ActivityMaster plugin, never an
IMasterSystem. Runtime calls require a verified organic user's credential, a
current installation on the authorized party, individual dependency consent and
administrator policy, in addition to the existing domain/row permissions. The
identity's optional installationPartyId defaults to the verified party; hosts
must verify membership before selecting an organization installation. Legacy
System credentials are retired by forward updates while registration IDs and
domain data are retained. Provisioning grants no user installation or consent.


`com.activity-master:document-master` provides stateless document storage and organization
on the internal FSDM. Its JPMS module is `com.guicedee.activitymaster.documents`; the
registered plugin is `Document Master`. The ActivityMaster BOM manages its version.

## Domain mapping

| Concern | Canonical FSDM rows |
| --- | --- |
| Document | `ResourceItem`, explicitly typed `Document` through `ResourceItemXResourceItemType` |
| Binary | Immutable `ResourceItemDataValue.resourceitemdatavalue` (`bytea`); no new `ResourceItemData` rows |
| Version | SCD `ResourceItemXClassification` named `DocumentVersion`; row ID is the version ID, value is the binary-value UUID |
| Filename, MIME type, byte length, SHA-256, title | `ResourceItemXClassification` |
| Categories and labels | Repeated `ResourceItemXClassification` values under `DocumentCategory` and `DocumentLabel` |
| Bucket, category container, group | `Arrangement`, typed `Document Bucket`, with name, kind and context classifications |
| Bucket contents | `ArrangementXResourceItem` classified `DocumentBucketDocument` |
| Bucket hierarchy | Parent/child `ArrangementXArrangement` classified `DocumentBucketChild` |
| Other arrangement to bucket | `ArrangementXArrangement` classified `DocumentBucketAttachment`; the other arrangement is the parent |
| Bucket owner/readers/editors | `ArrangementXInvolvedParty` classified `DocumentBucketMember`, with `OWNER`, `READER` or `EDITOR` value |
| Document owner | `InvolvedPartyXResourceItem` classified `DocumentOwner` |
| Party's document rating | `InvolvedPartyXResourceItem` classified `DocumentRating`, containing the score |
| Resource rating summary | `ResourceItemXClassification`: `DocumentRatingSum`, `DocumentRatingCount`, `DocumentRatingAverage` |

Ratings belong to the typed resource. Reading document metadata reads its stored
rating count and average and the caller's direct party/resource relationship; it
does not search or aggregate Events. Rating writes lock the resource, replace the
caller's effective rating relationship, and update sum/count/average in the same
transaction. Repeating a score is idempotent. Clearing a score updates the summary;
zero ratings means count and average are zero. Each party rates at most once at a time.
An existing score remains counted after bucket membership is revoked.

There are no separate document, bucket, metadata or rating tables.
`DocumentPluginInstall` at 1184 independently provisions the plugin registration,
catalogue and Core dependency through the normal forward update sweep, including
when the domain updates have already been recorded. `DocumentInstall`
installs resource/arrangement types and classifications against their correct data
concepts at update order 1185. `DocumentVersionInstall` at 1186 also installs the
version classification for enterprises where the original update has already run.
Neither update imports document contents or grants access.

## Identity and access

The consuming host binds `DocumentIdentityProvider.current()` to its verified,
call-scoped `DocumentIdentity(partyId, enterpriseId, context, identityToken,
installationPartyId)`. The four-argument constructor defaults installation to the
verified party. The default provider denies access. Path IDs and body data never
establish identity or the user's relationship to an organisation installation.

Personal and Social context owners must equal the verified party. Work context
owners must equal the authorized enterprise. Personal buckets cannot be shared.
Social members use their own verified party contexts while the bucket retains its
creator's context. A document can be linked to multiple buckets within the same
stored context, including the same Social owner; it cannot cross realm/owner boundaries.

Every operation, including reads and version history, checks current plugin
installation, dependency consent and administrator policy before the live actor,
identifying credential, enterprise and registration/party grants. Registration
read permission is always required;
creation also requires create permission and changes require update permission.
The API supplies a stateless transaction; direct `IDocumentService` writes require a
caller-owned stateless transaction. Compose operations by chaining their `Uni` values.

Readers can list/download documents and change their own ratings. Editors can also
upload. Only the bucket owner manages membership, nesting and arrangement attachments.
The resource owner alone changes its metadata, adds/removes its bucket links, or
archives it, and must still have access through a current bucket membership. Adding
or removing a document also requires editor access to the target bucket. Membership
changes and metadata replacement end previous relationships' effective dates.

Hierarchy and arrangement attachments organize buckets; access remains explicit per
bucket. Linking a parent does not grant access to its children. An arrangement
attachment additionally requires current read/update authority on the target
arrangement and its system. Private document/resource rows receive no public security
matrix. Hosts must keep privileged generic FSDM/raw database access behind their own
trusted service boundary.

Archiving ends the resource, current version pointer and bucket-content links' effective dates.
It uses update permission because it retains rows and payload history. Removing the
last bucket link is rejected; archive the document to remove it from use. Binaries
are immutable; publish a revision of the same resource for new content.

## Versions and SCD

Resource creation, immutable binary writes and binary reads go through
`IResourceItemService` (`createBinaryScopeRestricted`, `storeResourceDataValue`,
`getResourceDataValue`). The service owns the resource, type link and private row
security; Document Master owns authorization, bucket links and version/metadata
classifications. Binary revisions insert fresh values rather than calling the
legacy overwrite method. All writes share the caller's stateless transaction.

The resource ID identifies the document throughout its lifetime. Each revision is
an effective-dated `DocumentVersion` classification pointing directly at an
immutable `ResourceItemDataValue`. `Document.versionId` identifies the current
classification row. A version is not another resource or an Event; bucket links,
ownership and ratings remain on the original typed resource.

Publishing a revision locks that resource and compares `expectedVersionId` with
the current version. A stale request fails with REST 409 or GraphQL `CONFLICT`
before allocating a payload. The old version pointer and replaced metadata
classifications close at the same database timestamp that opens their successors.
SCD intervals are inclusive at `effectiveFrom`, exclusive at `effectiveTo`.
Previous rows and binaries are retained; they are not overwritten.

`updateMetadata` also appends a version, reusing the existing immutable binary
value. Restoring an earlier version appends a new version with that version's
content, filename, content type, title, categories and labels. It reuses the
retained binary and leaves intervening versions intact. Restoring also requires
`expectedVersionId`; it does not reset resource ratings or ownership.

Version history is paged newest first. `Version` contains `id`, `resourceId`,
`resourceType`, `effectiveFrom`, `effectiveTo`, `current` and the content/metadata
fields; it contains no payload or rating snapshot. Download content explicitly
for a chosen version. Every history/content request checks current document
access, so revocation and archive also deny historical reads. Only the resource
owner with current access may publish or restore; editor upload permission alone
does not authorize revising another party's resource.

The core resource reader follows the current `DocumentVersion` pointer for typed
Document resources. Existing migrated documents with binaries addressed by their
resource ID remain readable before a version marker exists. Their resource ID
acts as their initial version ID; the first revision/metadata write seeds its SCD
marker within the locked transaction. History for that initial version reflects
the last metadata state before the first versioned change. Earlier metadata-only
changes made before version support remain stored in their original SCD rows.

`ResourceItemData` is deprecated for removal and retained for legacy migration
compatibility. `ActivityMaster/core/src/main/resources/db/09.resourceitem.sql`
retains the legacy payload migration into `ResourceItemDataValue`. New document
storage, version history, downloads and archive do not use the deprecated table.

## Java API

Inject `DocumentApi` for authenticated host operations:

```java
return documents.createBucket(enterpriseName,
        new DocumentModels.CreateBucket("Invoices", DocumentModels.BucketKind.CATEGORY))
    .chain(bucket -> documents.upload(enterpriseName, bucket.id(),
        new DocumentModels.Upload("invoice.pdf", "application/pdf", pdfBytes,
            new DocumentModels.Metadata("September invoice",
                List.of("Accounts", "Invoices"), List.of("Reviewed")))))
    .chain(document -> documents.rate(enterpriseName, document.id(),
        new DocumentModels.Rate(5)));
```

The host identity supplies the context; the creation body does not select an owner.
`BucketKind` is `BUCKET`, `CATEGORY` or `GROUP`. Membership grants accept `READER` or
`EDITOR`; the creator remains `OWNER`. `Document` contains resource type, filename,
MIME type, size, checksum, title, categories, labels, rating average/count, the
caller's nullable rating and `versionId`. Metadata lists contain no binary payload.

```java
return documents.find(enterpriseName, resourceId)
    .chain(current -> documents.revise(enterpriseName, resourceId,
        new DocumentModels.Revise(current.versionId(),
            new DocumentModels.Upload("invoice-v2.pdf", "application/pdf", newBytes,
                new DocumentModels.Metadata("Corrected invoice", List.of("Invoices"), List.of())))));
```

## REST

Base: `/{enterprise}/documents`, beneath the consuming host's REST prefix.

| Method | Path | Body or query |
| --- | --- | --- |
| POST | `/buckets` | `{name, kind}` |
| GET | `/buckets` | Optional `parent`, `offset`, `limit` |
| GET | `/buckets/{bucket}` | Bucket metadata |
| GET | `/buckets/{bucket}/members` | Current members |
| PUT | `/buckets/{bucket}/members/{party}` | `{access: "READER"}` or `EDITOR` |
| DELETE | `/buckets/{bucket}/members/{party}` | Revoke membership |
| POST | `/buckets/{bucket}/documents` | `{filename, contentType, data, metadata: {title, categories, labels}}`; `data` is Base64 in JSON |
| GET | `/buckets/{bucket}/documents` | Optional exact `category`, exact `label`, title substring `search`, `offset`, `limit` |
| PUT / DELETE | `/buckets/{bucket}/documents/{resource}` | Add/remove an existing document |
| PUT / DELETE | `/buckets/{parent}/children/{child}` | Add/remove nesting; cycles are rejected |
| PUT / DELETE | `/buckets/{bucket}/arrangements/{arrangement}` | Attach/detach the bucket to/from another arrangement |
| GET | `/{resource}` | Document metadata and resource rating summary |
| GET | `/{resource}/content` | Binary attachment, stored MIME type, UTF-8 filename, no-store and nosniff headers |
| POST | `/{resource}/versions` | `{expectedVersionId, upload: {filename, contentType, data, metadata}}`; append a revision |
| GET | `/{resource}/versions` | Optional `offset`, `limit`; version metadata newest first |
| GET | `/{resource}/versions/{version}` | Version metadata and SCD interval |
| GET | `/{resource}/versions/{version}/content` | Historical binary attachment with that version's filename and MIME type |
| POST | `/{resource}/versions/{version}/restore` | `{expectedVersionId}`; append a restored version |
| PUT | `/{resource}/metadata` | `{title, categories, labels}` replaces those fields |
| PUT | `/{resource}/rating` | `{score: 1..5}` for the authenticated party |
| DELETE | `/{resource}/rating` | Clear the authenticated party's rating |
| DELETE | `/{resource}` | Archive the resource |

JSON results, including creation and clearing a rating, return 200. Membership,
link and archive operations return 204 with no body. Invalid UUIDs, paging, JSON,
Base64 or document input return 400. Missing/denied verified identity or an enterprise
mismatch returns 403; a resource or bucket outside the caller's current access returns
404. A stale expected version returns 409. Omitted `offset` and `limit` use their declared defaults. Download bytes retain
the stored MIME type and use an encoded UTF-8 attachment filename.

Uploads allow up to 16 MiB of decoded binary data, including an empty payload.
Names, titles, filenames, categories and labels are single-line valid Unicode with
at most 150 UTF-16 code units. Filenames cannot contain paths. Content types must be
`type/subtype` without parameters. Each document accepts up to 32 categories and
32 labels; duplicates collapse and comparisons are case-sensitive. Bucket membership
is limited to 100 parties, including the owner; invitees must be live organic parties.
Paging uses offset 0..10,000, limit 1..100, with default limit 50. Title search is a
case-insensitive literal substring. Inaccessible targets return 404. Render plain
metadata with escaping in consuming applications.

## GraphQL

`DocumentGraphQLSchemaProvider` contributes to the host's existing GuicedEE schema
through `IGraphQLSchemaProvider`, registered for both JPMS and classpath discovery.
It extends `Query` and `Mutation`; it does not start another HTTP server. The default
HTTP endpoint is `/graphql`, configurable with `GRAPHQL_HTTP_PATH`.

All resolvers delegate to the same `DocumentApi` used by REST. Every operation
requires `enterprise`; target IDs identify records and never establish identity.
For HTTP execution, the adapter places Vert.x's server-owned `RoutingContext`,
request and response in `CallScopeProperties` while subscribing to the operation.
The host's `DocumentIdentityProvider` resolves the verified identity from that
request, including the authorized context. Install the host's authentication
middleware before the terminal GraphQL handler. The default binding continues to deny.
Embedded execution must supply its own verified call scope and host binding.

| Query | Arguments beyond `enterprise` | Result |
| --- | --- | --- |
| `documentBucket` | `bucketId` | `DocumentBucket` |
| `documentBuckets` | Optional `parentId`, `offset = 0`, `limit = 50` | `DocumentBucketPage` |
| `documentBucketMembers` | `bucketId` | Current `DocumentMember` list |
| `document` | `resourceId` | `DocumentResource` metadata and resource ratings |
| `documents` | `bucketId`, optional `filter: {category, label, search}`, `offset = 0`, `limit = 50` | `DocumentPage` |
| `documentContent` | `resourceId` | `{filename, contentType, data}`; `data` is standard Base64 |
| `documentVersions` | `resourceId`, `offset = 0`, `limit = 50` | `DocumentVersionPage` |
| `documentVersion` | `resourceId`, `versionId` | Historical `DocumentVersion` metadata |
| `documentVersionContent` | `resourceId`, `versionId` | `{filename, contentType, data}`; `data` is standard Base64 |

| Mutation | Arguments beyond `enterprise` | Result |
| --- | --- | --- |
| `documentCreateBucket` | `input: {name, kind}` | `DocumentBucket` |
| `documentGrantMember` | `bucketId`, `partyId`, `access: READER` or `EDITOR` | `Boolean` |
| `documentRevokeMember` | `bucketId`, `partyId` | `Boolean` |
| `documentUpload` | `bucketId`, `input: {filename, contentType, data, metadata}` | `DocumentResource` |
| `documentRevise` | `resourceId`, `input: {expectedVersionId, upload: {filename, contentType, data, metadata}}` | `DocumentResource` |
| `documentRestoreVersion` | `resourceId`, `versionId`, `expectedVersionId` | `DocumentResource` |
| `documentUpdateMetadata` | `resourceId`, `input: {title, categories, labels}` | `DocumentResource` |
| `documentAddToBucket` / `documentRemoveFromBucket` | `bucketId`, `resourceId` | `Boolean` |
| `documentArchive` | `resourceId` | `Boolean` |
| `documentRate` | `resourceId`, `score: 1..5` | `DocumentResource` |
| `documentClearRating` | `resourceId` | `DocumentResource` |
| `documentAddChild` / `documentRemoveChild` | `parentId`, `childId` | `Boolean` |
| `documentAttachBucket` / `documentDetachBucket` | `arrangementId`, `bucketId` | `Boolean` |

Boolean mutation results are `true` only after successful completion. GraphQL
execution errors include `extensions.code`: `FORBIDDEN`, `NOT_FOUND`,
`BAD_USER_INPUT`, `CONFLICT` or `INTERNAL_SERVER_ERROR`, with the field path. Internal failure
details are logged server-side and excluded from responses. Schema validation
errors use GraphQL's standard validation response. GraphQL execution errors normally
return HTTP 200 with `errors`; check that envelope before treating a mutation as
successful.

`DocumentResource` exposes the same fields as the Java/REST `Document`, including
explicit `resourceType`, current `versionId` and nullable `myRating`. Size and rating count use the
`DocumentLong` scalar, serialized as 64-bit JSON integers. Clients needing exact
values beyond JavaScript's safe integer range must use an appropriate numeric parser.
Version effective dates are ISO-8601 offset timestamp strings.
Pages expose `items`, `offset`, `limit` and `hasMore`. Bucket kind, realm and member
access are enums. Metadata queries exclude content; `documentContent` requests it
explicitly. Uploads use standard Base64 and retain the 16 MiB decoded limit, with an
encoded-size check before allocating decoded bytes. Host HTTP body limits must
accommodate the Base64 payload and JSON envelope. Multipart uploads are not required.

```graphql
query Documents($enterprise: String!, $bucket: ID!) {
  documents(enterprise: $enterprise, bucketId: $bucket,
            filter: { category: "Invoices", label: "Reviewed" }, limit: 20) {
    items { id resourceType title labels ratingAverage ratingCount myRating }
    offset limit hasMore
  }
}

mutation RateDocument($enterprise: String!, $resource: ID!) {
  documentRate(enterprise: $enterprise, resourceId: $resource, score: 5) {
    id resourceType ratingAverage ratingCount myRating
  }
}
```

## Validation

Run from this module, without cleaning:

```powershell
mvn test "-Dtest=DocumentStorageTest,DocumentRestTest,DocumentGraphQLTest,DocumentVersionTest" "-Dmaven.test.failure.ignore=false"
```

`DocumentStorageTest` uses PostgreSQL 17 Testcontainers and the canonical FSDM schema.
It covers typed-resource/binary persistence, metadata filtering and history, membership
revocation, context separation, current grant checks, bucket cycles, arrangement
authority, multi-bucket links, archive retention, transaction rollback, rating changes,
concurrent ratings and absence of rating Events.

`DocumentVersionTest` checks contiguous SCD boundaries, retained binary/metadata
snapshots, stable ratings, metadata-only payload reuse, stale concurrent writers,
rollback without orphan payloads, restore-as-new-version, current history authority,
legacy payload compatibility and the core resource reader. Version operations leave
the deprecated data table unchanged. REST and GraphQL tests also exercise version
publication, history, binary retrieval, restore and conflict responses over HTTP.

The opt-in `DocumentQueryPerformanceTest` exercises every service operation and
captures native SQL with its actual parameters and paging. PostgreSQL plans,
measurements, index choices and the reproduction command are documented in
[the query performance audit](docs/query-performance.md).

`DocumentRestTest` starts the real GuicedEE web/REST lifecycle on an ephemeral loopback
port and uses HTTP requests against the same PostgreSQL storage. It exercises literal
bucket route selection, default/query parameter binding, record JSON and Base64
uploads, exact binary downloads, UTF-8 attachment headers, metadata filters, ratings,
membership revocation, enterprise separation, invalid input and archive responses.
`DocumentGraphQLTest` sends HTTP queries and mutations to the SPI-composed `/graphql`
endpoint against the same canonical storage. It covers every document root field,
typed record/enum serialization, Base64 round trips, paging/filtering, ratings,
membership shared with REST, denied and revoked access, enterprise mismatch,
hierarchy, arrangement attachments, archive and validation errors. It also checks
concurrent request identity isolation, ordered mutations within one request, and
rating counts beyond GraphQL's 32-bit `Int` range.

The test host resolves opaque fixture credentials to per-request identities; that
binding exists only in test sources. This verifies transport behavior with a test
host, not production host authentication or browser/deployment integration.

GuicedEE REST must include the route-ordering and `@DefaultValue` fixes in
`OperationRegistry` and `ParameterExtractor`. Build/install `GuicedEE/rest` locally
before testing if consuming an older resolved REST artifact. Inspect Surefire's
failure/error counts; Maven test failures must not be ignored.
