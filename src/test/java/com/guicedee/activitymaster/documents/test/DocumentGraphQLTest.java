package com.guicedee.activitymaster.documents.test;

import com.guicedee.activitymaster.documents.DocumentIdentity;
import com.guicedee.activitymaster.fsdm.client.services.IArrangementsService;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import com.guicedee.client.IGuiceContext;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.guicedee.client.implementations.ObjectBinderKeys.DefaultObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the SPI-composed schema through the actual GraphQL HTTP endpoint and FSDM database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentGraphQLTest {
    private DocumentTestFixture fixture;
    private HttpClient client;
    private ObjectMapper mapper;
    private URI endpoint;
    private String owner, reader, outsider;
    private static final String RESOURCE = "{ id resourceType filename contentType size sha256 title categories labels ratingAverage ratingCount myRating versionId }";

    @BeforeAll void setup() throws Exception {
        fixture = DocumentTestFixture.get();
        owner = DocumentTestHost.issue(identity(fixture.actorId, fixture.enterpriseId));
        reader = DocumentTestHost.issue(identity(fixture.recipientId, fixture.enterpriseId));
        outsider = DocumentTestHost.issue(identity(fixture.outsiderId, fixture.enterpriseId));
        mapper = IGuiceContext.get(DefaultObjectMapper);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (DocumentTestHost.server == null || DocumentTestHost.server.actualPort() <= 0) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("GuicedEE HTTP listener did not start");
            Thread.sleep(50);
        }
        endpoint = URI.create("http://127.0.0.1:" + DocumentTestHost.server.actualPort() + "/graphql");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    @AfterAll void closeClient() { if (client != null) client.close(); }
    private DocumentIdentity identity(UUID party, UUID enterprise) {
        return new DocumentIdentity(party, enterprise, new ActivityScope.Context(ActivityScope.Realm.WORK, enterprise), fixture.token);
    }
    private JsonNode execute(String query, Map<String, Object> variables, String credential) throws Exception {
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("query", query, "variables", variables))));
        if (credential != null) request.header("X-Document-Test-Credential", credential);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }
    private JsonNode field(boolean mutation, String field, String args, String selection, String inputType, Map<String, Object> variables, String credential) throws Exception {
        String declarations = inputType == null ? "" : "($input: " + inputType + "!)";
        String query = (mutation ? "mutation" : "query") + declarations + " { " + field
                + "(enterprise: \"" + DocumentTestFixture.ENTERPRISE + "\"" + (args.isEmpty() ? "" : ", " + args) + ") " + selection + " }";
        return execute(query, variables, credential);
    }
    private JsonNode read(String field, String args, String selection, String credential) throws Exception {
        return field(false, field, args, selection, null, Map.of(), credential);
    }
    private JsonNode write(String field, String args, String selection, String credential) throws Exception {
        return field(true, field, args, selection, null, Map.of(), credential);
    }
    private JsonNode result(JsonNode response, String field) {
        assertFalse(response.has("errors"), response.toString());
        assertTrue(response.has("data"), response.toString());
        return response.get("data").get(field);
    }
    private void error(JsonNode response, String code) {
        assertTrue(response.has("errors"), response.toString());
        assertEquals(code, response.path("errors").get(0).path("extensions").path("code").asString(), response.toString());
        assertTrue(response.get("data").isNull(), response.toString());
    }
    private static String target(String name, String id) { return name + ": \"" + id + "\""; }
    private String bucket(String name) throws Exception {
        return result(field(true, "documentCreateBucket", "input: $input", "{ id name kind realm ownerId }", "DocumentCreateBucketInput",
                Map.of("input", Map.of("name", name, "kind", "GROUP")), owner), "documentCreateBucket").get("id").asString();
    }
    private JsonNode upload(String bucket, String filename, byte[] bytes, Map<String, Object> metadata) throws Exception {
        return result(field(true, "documentUpload", target("bucketId", bucket) + ", input: $input", RESOURCE, "DocumentUploadInput",
                Map.of("input", Map.of("filename", filename, "contentType", "application/pdf", "data", Base64.getEncoder().encodeToString(bytes), "metadata", metadata)), owner), "documentUpload");
    }
    private String document(String bucket) throws Exception {
        return upload(bucket, "invoice.pdf", new byte[]{1}, Map.of("title", "September invoice", "categories", List.of("Invoices"), "labels", List.of("Reviewed"))).get("id").asString();
    }
    private void grant(String bucket, String access) throws Exception {
        assertTrue(result(write("documentGrantMember", target("bucketId", bucket) + ", " + target("partyId", fixture.recipientId.toString()) + ", access: " + access, "", owner), "documentGrantMember").asBoolean());
    }

    @Test void discoversTypedSchemaAndRoundTripsBinaryWithoutPuttingContentInMetadata() throws Exception {
        String bucket = bucket("GraphQL binary");
        JsonNode found = result(read("documentBucket", target("bucketId", bucket), "{ id name kind realm ownerId }", owner), "documentBucket");
        assertEquals("GROUP", found.get("kind").asString()); assertEquals("WORK", found.get("realm").asString());
        assertEquals(fixture.enterpriseId.toString(), found.get("ownerId").asString());
        byte[] bytes = {0, -1, 1, 2, 13, 10};
        JsonNode uploaded = upload(bucket, "résumé.pdf", bytes, Map.of("title", "Binary"));
        assertEquals("Document", uploaded.get("resourceType").asString());
        assertEquals(6L, uploaded.get("size").asLong()); assertEquals(64, uploaded.get("sha256").asString().length());
        assertEquals(0, uploaded.get("categories").size()); assertTrue(uploaded.get("myRating").isNull());
        assertFalse(uploaded.has("data"));
        String resource = uploaded.get("id").asString();
        JsonNode content = result(read("documentContent", target("resourceId", resource), "{ filename contentType data }", owner), "documentContent");
        assertEquals("résumé.pdf", content.get("filename").asString()); assertEquals("application/pdf", content.get("contentType").asString());
        assertArrayEquals(bytes, Base64.getDecoder().decode(content.get("data").asString()));
        JsonNode aliases = execute("{ a: document(enterprise: \"DocumentTest\", resourceId: \"" + resource + "\") { id }"
                + " b: documentContent(enterprise: \"DocumentTest\", resourceId: \"" + resource + "\") { data } }", Map.of(), owner);
        assertEquals(resource, result(aliases, "a").get("id").asString());
        assertArrayEquals(bytes, Base64.getDecoder().decode(result(aliases, "b").get("data").asString()));
    }

    @Test void pagesAndFiltersMetadataThroughVariablesAndLiteralInputs() throws Exception {
        String bucket = bucket("GraphQL paging"), resource = document(bucket);
        document(bucket);
        JsonNode buckets = result(read("documentBuckets", "", "{ offset limit hasMore items { id } }", owner), "documentBuckets");
        assertEquals(0, buckets.get("offset").asInt()); assertEquals(50, buckets.get("limit").asInt());
        JsonNode page = result(read("documents", target("bucketId", bucket) + ", filter: {category: \"Invoices\", label: \"Reviewed\", search: \"SEPTEMBER\"}", "{ offset limit hasMore items " + RESOURCE + " }", owner), "documents");
        assertEquals(2, page.get("items").size()); assertEquals(50, page.get("limit").asInt());
        JsonNode first = result(read("documents", target("bucketId", bucket) + ", limit: 1", "{ hasMore items { id } }", owner), "documents");
        assertTrue(first.get("hasMore").asBoolean()); assertEquals(resource, first.get("items").get(0).get("id").asString());
        JsonNode updated = result(field(true, "documentUpdateMetadata", target("resourceId", resource) + ", input: $input", RESOURCE, "DocumentMetadataInput",
                Map.of("input", Map.of("title", "Receipt", "categories", List.of("Receipts"), "labels", List.of("Paid"))), owner), "documentUpdateMetadata");
        assertEquals("Receipt", updated.get("title").asString());
        JsonNode filtered = result(read("documents", target("bucketId", bucket) + ", filter: {category: \"Receipts\", label: \"Paid\", search: \"receipt\"}", "{ items { id } }", owner), "documents");
        assertEquals(1, filtered.get("items").size());
    }

    @Test void sharesMembershipWithRestAndNeverReusesAnotherRequestsIdentity() throws Exception {
        String bucket = bucket("GraphQL authority"), resource = document(bucket);
        error(read("document", target("resourceId", resource), RESOURCE, null), "FORBIDDEN");
        error(read("document", target("resourceId", resource), RESOURCE, UUID.randomUUID().toString()), "FORBIDDEN");
        error(read("documentContent", target("resourceId", resource), "{ data }", outsider), "NOT_FOUND");
        grant(bucket, "READER");
        JsonNode members = result(read("documentBucketMembers", target("bucketId", bucket), "{ partyId access }", owner), "documentBucketMembers");
        assertEquals(2, members.size());
        assertEquals(resource, result(read("document", target("resourceId", resource), RESOURCE, reader), "document").get("id").asString());
        error(write("documentUpdateMetadata", target("resourceId", resource) + ", input: {title: \"Denied\"}", RESOURCE, reader), "NOT_FOUND");
        error(write("documentGrantMember", target("bucketId", bucket) + ", " + target("partyId", fixture.outsiderId.toString()) + ", access: EDITOR", "", reader), "NOT_FOUND");
        String restUrl = "http://127.0.0.1:" + DocumentTestHost.server.actualPort() + "/DocumentTest/documents/" + resource;
        var response = client.send(HttpRequest.newBuilder(URI.create(restUrl)).header("X-Document-Test-Credential", reader).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(result(write("documentRevokeMember", target("bucketId", bucket) + ", " + target("partyId", fixture.recipientId.toString()), "", owner), "documentRevokeMember").asBoolean());
        error(read("document", target("resourceId", resource), RESOURCE, reader), "NOT_FOUND");
        error(read("document", target("resourceId", resource), RESOURCE, null), "FORBIDDEN");
        assertEquals(resource, result(read("document", target("resourceId", resource), RESOURCE, owner), "document").get("id").asString());
        String mismatch = DocumentTestHost.issue(identity(fixture.actorId, UUID.randomUUID()));
        error(read("document", target("resourceId", resource), RESOURCE, mismatch), "FORBIDDEN");
    }

    @Test void ratesTheTypedResourceForEachVerifiedPartyAndClearsOnlyThatPartysRating() throws Exception {
        String bucket = bucket("GraphQL ratings"), resource = document(bucket);
        grant(bucket, "READER");
        result(write("documentRate", target("resourceId", resource) + ", score: 5", RESOURCE, owner), "documentRate");
        JsonNode rated = result(write("documentRate", target("resourceId", resource) + ", score: 3", RESOURCE, reader), "documentRate");
        assertEquals(2L, rated.get("ratingCount").asLong()); assertEquals(4.0, rated.get("ratingAverage").asDouble());
        assertEquals(3, rated.get("myRating").asInt()); assertEquals("Document", rated.get("resourceType").asString());
        assertEquals(5, result(read("document", target("resourceId", resource), RESOURCE, owner), "document").get("myRating").asInt());
        JsonNode changed = result(write("documentRate", target("resourceId", resource) + ", score: 1", RESOURCE, reader), "documentRate");
        assertEquals(2L, changed.get("ratingCount").asLong()); assertEquals(3.0, changed.get("ratingAverage").asDouble());
        JsonNode cleared = result(write("documentClearRating", target("resourceId", resource), RESOURCE, reader), "documentClearRating");
        assertEquals(1L, cleared.get("ratingCount").asLong()); assertEquals(5.0, cleared.get("ratingAverage").asDouble());
        assertTrue(cleared.get("myRating").isNull());
    }

    @Test void routesHierarchyContentLinksAttachmentsAndArchiveMutationsToCanonicalStorage() throws Exception {
        String parent = bucket("GraphQL parent"), child = bucket("GraphQL child"), resource = document(parent);
        String relation = target("parentId", parent) + ", " + target("childId", child);
        assertTrue(result(write("documentAddChild", relation, "", owner), "documentAddChild").asBoolean());
        JsonNode children = result(read("documentBuckets", target("parentId", parent), "{ items { id } }", owner), "documentBuckets");
        assertEquals(child, children.get("items").get(0).get("id").asString());
        error(write("documentAddChild", target("parentId", child) + ", " + target("childId", parent), "", owner), "BAD_USER_INPUT");
        assertTrue(result(write("documentAddToBucket", target("bucketId", child) + ", " + target("resourceId", resource), "", owner), "documentAddToBucket").asBoolean());
        assertTrue(result(write("documentRemoveFromBucket", target("bucketId", parent) + ", " + target("resourceId", resource), "", owner), "documentRemoveFromBucket").asBoolean());
        error(write("documentRemoveFromBucket", target("bucketId", child) + ", " + target("resourceId", resource), "", owner), "BAD_USER_INPUT");
        assertTrue(result(write("documentRemoveChild", relation, "", owner), "documentRemoveChild").asBoolean());
        UUID arrangement = fixture.run(c -> {
            IArrangementsService<?> arrangements = IGuiceContext.get(IArrangementsService.class);
            return arrangements.createArrangementType(c.getItem1(), "GraphQL Attachment Target", c.getItem3(), fixture.token)
                    .chain(() -> arrangements.create(c.getItem1(), "GraphQL Attachment Target", UUID.randomUUID(), "DocumentBucketType", "1", c.getItem3(), fixture.token)).map(a -> a.getId());
        });
        String attachment = target("arrangementId", arrangement.toString()) + ", " + target("bucketId", child);
        assertTrue(result(write("documentAttachBucket", attachment, "", owner), "documentAttachBucket").asBoolean());
        error(write("documentDetachBucket", attachment, "", outsider), "NOT_FOUND");
        assertTrue(result(write("documentDetachBucket", attachment, "", owner), "documentDetachBucket").asBoolean());
        assertTrue(result(write("documentArchive", target("resourceId", resource), "", owner), "documentArchive").asBoolean());
        error(read("documentContent", target("resourceId", resource), "{ data }", owner), "NOT_FOUND");
    }

    @Test void rejectsMalformedAndUnauthorizedInputWithoutClaimingMutationSuccess() throws Exception {
        String bucket = bucket("GraphQL validation"), resource = document(bucket);
        error(read("document", "resourceId: \"invalid-uuid\"", RESOURCE, owner), "BAD_USER_INPUT");
        error(read("documents", target("bucketId", bucket) + ", limit: 0", "{ items { id } }", owner), "BAD_USER_INPUT");
        error(write("documentRate", target("resourceId", resource) + ", score: 6", RESOURCE, owner), "BAD_USER_INPUT");
        error(field(true, "documentUpload", target("bucketId", bucket) + ", input: $input", RESOURCE, "DocumentUploadInput",
                Map.of("input", Map.of("filename", "bad.pdf", "contentType", "application/pdf", "data", "bad base64!", "metadata", Map.of("title", "Bad"))), owner), "BAD_USER_INPUT");
        JsonNode unknownIdentity = field(true, "documentCreateBucket", "input: $input", "{ id }", "DocumentCreateBucketInput",
                Map.of("input", Map.of("name", "Forged", "kind", "BUCKET", "ownerId", fixture.outsiderId.toString())), owner);
        assertTrue(unknownIdentity.has("errors"), unknownIdentity.toString());
        JsonNode invalidEnum = write("documentGrantMember", target("bucketId", bucket) + ", " + target("partyId", fixture.recipientId.toString()) + ", access: OWNER", "", owner);
        assertTrue(invalidEnum.has("errors"), invalidEnum.toString());
        JsonNode nullPage = read("documents", target("bucketId", bucket) + ", limit: null", "{ items { id } }", owner);
        assertTrue(nullPage.has("errors"), nullPage.toString());
        assertEquals(resource, result(read("document", target("resourceId", resource), RESOURCE, owner), "document").get("id").asString());
    }

    @Test void isolatesConcurrentRequestIdentitiesAndExecutesSerialMutationsInOrder() throws Exception {
        String bucket = bucket("GraphQL concurrency"), resource = document(bucket);
        String query = "{ document(enterprise: \"DocumentTest\", resourceId: \"" + resource + "\") { id } }";
        String json = mapper.writeValueAsString(Map.of("query", query));
        var pending = new java.util.ArrayList<CompletableFuture<HttpResponse<String>>>();
        for (int i = 0; i < 9; i++) {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (i % 3 != 2) request.header("X-Document-Test-Credential", i % 3 == 0 ? owner : outsider);
            pending.add(client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()));
        }
        for (int i = 0; i < pending.size(); i++) {
            var response = pending.get(i).get(20, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode envelope = mapper.readTree(response.body());
            if (i % 3 == 0) assertEquals(resource, result(envelope, "document").get("id").asString());
            else error(envelope, i % 3 == 1 ? "NOT_FOUND" : "FORBIDDEN");
        }
        String mutations = "mutation { first: documentRate(enterprise: \"DocumentTest\", resourceId: \"" + resource + "\", score: 5) { myRating ratingCount }"
                + " second: documentClearRating(enterprise: \"DocumentTest\", resourceId: \"" + resource + "\") { myRating ratingCount } }";
        JsonNode serial = execute(mutations, Map.of(), owner);
        assertEquals(5, result(serial, "first").get("myRating").asInt());
        assertEquals(1L, result(serial, "first").get("ratingCount").asLong());
        assertTrue(result(serial, "second").get("myRating").isNull());
        assertEquals(0L, result(serial, "second").get("ratingCount").asLong());
    }

    @Test void serializesStoredRatingCountsBeyondGraphqlIntRange() throws Exception {
        String bucket = bucket("GraphQL long count"), resource = document(bucket);
        long count = (long) Integer.MAX_VALUE + 1;
        // Seed a large existing summary without manufacturing billions of party relationships.
        fixture.run(c -> c.getItem1().createNativeQuery("update resource.resourceitemxclassification x set value=:count"
                        + " from classification.classification cl where cl.classificationid=x.classificationid"
                        + " and cl.classificationname='DocumentRatingCount' and x.resourceitemid=:id"
                        + " and x.effectivetodate>statement_timestamp()")
                .setParameter("count", Long.toString(count)).setParameter("id", UUID.fromString(resource))
                .executeUpdate().invoke(changed -> assertEquals(1, changed)));
        JsonNode metadata = result(read("document", target("resourceId", resource), "{ id ratingCount }", owner), "document");
        assertTrue(metadata.get("ratingCount").isIntegralNumber());
        assertEquals(count, metadata.get("ratingCount").asLong());
    }

    @Test void versionsRestoresAndDownloadsHistoricalContentThroughGraphql() throws Exception {
        String bucket = bucket("GraphQL versions");
        JsonNode first = upload(bucket, "first.pdf", new byte[]{0, -1, 2}, Map.of("title", "First"));
        String resource = first.get("id").asString(), initial = first.get("versionId").asString();
        Map<String, Object> revision = Map.of("input", Map.of("expectedVersionId", initial, "upload",
                Map.of("filename", "second.txt", "contentType", "text/plain", "data", Base64.getEncoder().encodeToString(new byte[]{5}), "metadata", Map.of("title", "Second"))));
        JsonNode second = result(field(true, "documentRevise", target("resourceId", resource) + ", input: $input", RESOURCE, "DocumentReviseInput", revision, owner), "documentRevise");
        String next = second.get("versionId").asString(); assertNotEquals(initial, next); assertEquals(resource, second.get("id").asString());
        error(field(true, "documentRevise", target("resourceId", resource) + ", input: $input", RESOURCE, "DocumentReviseInput", revision, owner), "CONFLICT");
        String historical = target("resourceId", resource) + ", " + target("versionId", initial);
        JsonNode old = result(read("documentVersion", historical, "{ id resourceId resourceType effectiveFrom effectiveTo current filename title size sha256 categories labels }", owner), "documentVersion");
        assertEquals("First", old.get("title").asString()); assertFalse(old.get("current").asBoolean());
        assertFalse(old.get("effectiveFrom").asString().isBlank());
        JsonNode page = result(read("documentVersions", target("resourceId", resource) + ", limit: 1", "{ offset limit hasMore items { id current } }", owner), "documentVersions");
        assertEquals(next, page.get("items").get(0).get("id").asString()); assertTrue(page.get("hasMore").asBoolean());
        JsonNode content = result(read("documentVersionContent", historical, "{ filename contentType data }", owner), "documentVersionContent");
        assertEquals("first.pdf", content.get("filename").asString()); assertArrayEquals(new byte[]{0, -1, 2}, Base64.getDecoder().decode(content.get("data").asString()));
        JsonNode restored = result(write("documentRestoreVersion", historical + ", " + target("expectedVersionId", next), RESOURCE, owner), "documentRestoreVersion");
        assertEquals("First", restored.get("title").asString()); assertNotEquals(initial, restored.get("versionId").asString());
        error(read("documentVersionContent", historical, "{ data }", outsider), "NOT_FOUND");
        error(read("documentVersions", target("resourceId", resource) + ", limit: 0", "{ items { id } }", owner), "BAD_USER_INPUT");
        error(read("documentVersion", target("resourceId", resource) + ", " + target("versionId", UUID.randomUUID().toString()), "{ id }", owner), "NOT_FOUND");
    }
}
