package com.guicedee.activitymaster.documents.test;

import com.guicedee.activitymaster.documents.*;
import com.guicedee.activitymaster.documents.DocumentModels.*;
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
import java.util.List;
import java.util.UUID;
import static com.guicedee.client.implementations.ObjectBinderKeys.DefaultObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Actual GuicedEE HTTP routing/JSON/binary behavior backed by PostgreSQL FSDM storage. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentRestTest {
    private DocumentTestFixture fixture;
    private HttpClient client;
    private ObjectMapper mapper;
    private String base, owner, reader, outsider;

    @BeforeAll void setup() throws Exception {
        fixture = DocumentTestFixture.get();
        owner = DocumentTestHost.issue(identity(fixture.actorId));
        reader = DocumentTestHost.issue(identity(fixture.recipientId));
        outsider = DocumentTestHost.issue(identity(fixture.outsiderId));
        mapper = IGuiceContext.get(DefaultObjectMapper);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (DocumentTestHost.server == null || DocumentTestHost.server.actualPort() <= 0) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("GuicedEE HTTP listener did not start");
            Thread.sleep(50);
        }
        base = "http://127.0.0.1:" + DocumentTestHost.server.actualPort() + "/" + DocumentTestFixture.ENTERPRISE + "/documents";
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    @AfterAll void closeClient() { if (client != null) client.close(); }
    private DocumentIdentity identity(UUID party) {
        return new DocumentIdentity(party, fixture.enterpriseId,
                new ActivityScope.Context(ActivityScope.Realm.WORK, fixture.enterpriseId), fixture.tokenFor(party));
    }
    private HttpRequest request(String method, String path, String credential, String json) {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
        if (credential != null) request.header("X-Document-Test-Credential", credential);
        if (json != null) request.header("Content-Type", "application/json");
        return request.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json)).build();
    }
    private HttpResponse<String> call(String method, String path, String credential, Object body) throws Exception {
        String json = body == null ? null : mapper.writeValueAsString(body);
        return client.send(request(method, path, credential, json), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode success(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }
    private String bucket(String name) throws Exception {
        return success(call("POST", "/buckets", owner, new CreateBucket(name, BucketKind.BUCKET)), 200).get("id").asString();
    }
    private String upload(String bucket, String filename, byte[] bytes) throws Exception {
        return success(call("POST", "/buckets/" + bucket + "/documents", owner,
                new Upload(filename, "application/pdf", bytes, new Metadata("September invoice", List.of("Invoices"), List.of("Reviewed")))), 200).get("id").asString();
    }
    private void share(String bucket) throws Exception {
        var response = call("PUT", "/buckets/" + bucket + "/members/" + fixture.recipientId, owner, new Grant(Access.READER));
        assertEquals(204, response.statusCode(), response.body()); assertTrue(response.body().isEmpty());
    }

    @Test void roundTripsJsonUploadAndBinaryDownloadWithUtf8Filename() throws Exception {
        String bucket = bucket("HTTP binary"), resource = upload(bucket, "résumé* report.pdf", new byte[]{0, -1, 1, 2, 13, 10});
        JsonNode metadata = success(call("GET", "/" + resource, owner, null), 200);
        assertEquals("Document", metadata.get("resourceType").asString());
        assertEquals("résumé* report.pdf", metadata.get("filename").asString());
        assertEquals(6, metadata.get("size").asInt());
        assertFalse(metadata.has("data"));
        HttpResponse<byte[]> download = client.send(request("GET", "/" + resource + "/content", owner, null), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, download.statusCode());
        assertArrayEquals(new byte[]{0, -1, 1, 2, 13, 10}, download.body());
        assertEquals("application/pdf", download.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("attachment; filename*=UTF-8''r%C3%A9sum%C3%A9%2A%20report.pdf", download.headers().firstValue("Content-Disposition").orElseThrow());
        assertEquals("no-store", download.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("nosniff", download.headers().firstValue("X-Content-Type-Options").orElseThrow());
    }

    @Test void appliesDefaultPagingAndBindsFiltersThroughHttp() throws Exception {
        String bucket = bucket("HTTP paging"), resource = upload(bucket, "first.pdf", new byte[0]);
        upload(bucket, "second.pdf", new byte[]{1});
        JsonNode buckets = success(call("GET", "/buckets", owner, null), 200);
        assertEquals(0, buckets.path("offset").asInt()); assertEquals(50, buckets.path("limit").asInt());
        JsonNode page = success(call("GET", "/buckets/" + bucket + "/documents?category=Invoices&label=Reviewed&search=SEPTEMBER", owner, null), 200);
        assertEquals(50, page.path("limit").asInt()); assertEquals(2, page.get("items").size());
        JsonNode first = success(call("GET", "/buckets/" + bucket + "/documents?limit=1", owner, null), 200);
        assertTrue(first.get("hasMore").asBoolean()); assertEquals(resource, first.get("items").get(0).get("id").asString());
        JsonNode updated = success(call("PUT", "/" + resource + "/metadata", owner, new Metadata("Receipt", List.of("Receipts"), List.of("Paid"))), 200);
        assertEquals("Receipt", updated.get("title").asString());
        JsonNode filtered = success(call("GET", "/buckets/" + bucket + "/documents?category=Receipts&label=Paid&search=receipt&offset=0&limit=10", owner, null), 200);
        assertEquals(1, filtered.get("items").size());
    }

    @Test void bindsEachRequestToVerifiedHostIdentityAndRevokesAccessImmediately() throws Exception {
        String bucket = bucket("HTTP membership"), resource = upload(bucket, "private.pdf", new byte[]{1});
        assertEquals(403, call("GET", "/" + resource, null, null).statusCode());
        assertEquals(403, call("GET", "/" + resource, UUID.randomUUID().toString(), null).statusCode());
        assertEquals(404, call("GET", "/" + resource + "/content", outsider, null).statusCode());
        share(bucket);
        assertEquals(200, call("GET", "/" + resource + "/content", reader, null).statusCode());
        assertEquals(404, call("PUT", "/" + resource + "/metadata", reader, new Metadata("Denied", List.of(), List.of())).statusCode());
        assertEquals(404, call("PUT", "/buckets/" + bucket + "/members/" + fixture.outsiderId, reader, new Grant(Access.EDITOR)).statusCode());
        var revoke = call("DELETE", "/buckets/" + bucket + "/members/" + fixture.recipientId, owner, null);
        assertEquals(204, revoke.statusCode()); assertTrue(revoke.body().isEmpty());
        assertEquals(404, call("GET", "/" + resource + "/content", reader, null).statusCode());
        assertEquals(200, call("GET", "/" + resource, owner, null).statusCode());
        assertEquals(404, call("GET", "/" + resource, outsider, null).statusCode());
    }

    @Test void ratesTypedResourcesFromIndependentHttpRequests() throws Exception {
        String bucket = bucket("HTTP ratings"), resource = upload(bucket, "ratings.pdf", new byte[]{1});
        share(bucket);
        success(call("PUT", "/" + resource + "/rating", owner, new Rate(5)), 200);
        JsonNode second = success(call("PUT", "/" + resource + "/rating", reader, new Rate(3)), 200);
        assertEquals(2, second.get("ratingCount").asLong()); assertEquals(4, second.get("ratingAverage").asDouble());
        assertEquals(3, second.get("myRating").asInt());
        JsonNode mine = success(call("GET", "/" + resource, owner, null), 200);
        assertEquals(5, mine.get("myRating").asInt());
        JsonNode changed = success(call("PUT", "/" + resource + "/rating", reader, new Rate(1)), 200);
        assertEquals(3, changed.get("ratingAverage").asDouble());
        JsonNode cleared = success(call("DELETE", "/" + resource + "/rating", reader, null), 200);
        assertEquals(1, cleared.get("ratingCount").asLong()); assertEquals(5, cleared.get("ratingAverage").asDouble());
    }

    @Test void returnsClientErrorsForMalformedBindingsAndDomainInput() throws Exception {
        String bucket = bucket("HTTP invalid"), resource = upload(bucket, "valid.pdf", new byte[]{1});
        assertEquals(400, call("GET", "/not-a-uuid", owner, null).statusCode());
        assertEquals(400, call("GET", "/buckets/" + bucket + "/documents?offset=no&limit=1", owner, null).statusCode());
        assertEquals(400, call("GET", "/buckets/" + bucket + "/documents?offset=0&limit=0", owner, null).statusCode());
        assertEquals(400, call("PUT", "/" + resource + "/rating", owner, new Rate(6)).statusCode());
        assertEquals(400, call("POST", "/buckets", owner, new CreateBucket("bad\nname", BucketKind.BUCKET)).statusCode());
        assertEquals(400, client.send(request("POST", "/buckets", owner, "{bad-json"), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, client.send(request("POST", "/buckets/" + bucket + "/documents", owner,
                "{\"filename\":\"bad.pdf\",\"contentType\":\"application/pdf\",\"data\":\"invalid base64!\",\"metadata\":{\"title\":\"Bad\"}}"), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, call("POST", "/buckets/" + bucket + "/documents", owner,
                new Upload("../bad.pdf", "application/pdf", new byte[]{1}, new Metadata("Invalid", List.of(), List.of()))).statusCode());
        assertEquals(200, call("GET", "/" + resource, owner, null).statusCode());
    }

    @Test void rejectsEnterpriseMismatchWithoutTrustingPathOrPayloadIdentity() throws Exception {
        UUID otherEnterprise = UUID.randomUUID();
        String mismatched = DocumentTestHost.issue(new DocumentIdentity(fixture.actorId, otherEnterprise,
                new ActivityScope.Context(ActivityScope.Realm.WORK, otherEnterprise), fixture.token));
        assertEquals(403, call("POST", "/buckets", mismatched, new CreateBucket("Denied", BucketKind.BUCKET)).statusCode());
        JsonNode bucket = success(client.send(request("POST", "/buckets", owner,
                "{\"name\":\"Verified context\",\"kind\":\"BUCKET\",\"ownerId\":\"" + fixture.outsiderId + "\",\"partyId\":\"" + fixture.outsiderId + "\"}"), HttpResponse.BodyHandlers.ofString()), 200);
        assertEquals(fixture.enterpriseId.toString(), bucket.get("ownerId").asString());
    }

    @Test void exercisesBucketNestingContentLinksAndArchiveHttpStatuses() throws Exception {
        String parent = bucket("HTTP parent"), child = bucket("HTTP child"), resource = upload(parent, "linked.pdf", new byte[]{0});
        assertEquals(204, call("PUT", "/buckets/" + parent + "/children/" + child, owner, null).statusCode());
        JsonNode children = success(call("GET", "/buckets?parent=" + parent + "&offset=0&limit=10", owner, null), 200);
        assertEquals(child, children.get("items").get(0).get("id").asString());
        assertEquals(400, call("PUT", "/buckets/" + child + "/children/" + parent, owner, null).statusCode());
        assertEquals(204, call("PUT", "/buckets/" + child + "/documents/" + resource, owner, null).statusCode());
        assertEquals(204, call("DELETE", "/buckets/" + parent + "/documents/" + resource, owner, null).statusCode());
        assertEquals(204, call("DELETE", "/buckets/" + parent + "/children/" + child, owner, null).statusCode());
        assertEquals(204, call("DELETE", "/" + resource, owner, null).statusCode());
        assertEquals(404, call("GET", "/" + resource + "/content", owner, null).statusCode());
    }

    @Test void versionsContentAndMetadataThroughHttpAndRejectsStaleUpdates() throws Exception {
        String bucket = bucket("HTTP versions"), resource = upload(bucket, "initial.pdf", new byte[]{0, -1, 2});
        String initial = success(call("GET", "/" + resource, owner, null), 200).get("versionId").asString();
        Revise revision = new Revise(UUID.fromString(initial), new Upload("next.txt", "text/plain", new byte[]{5, 6}, new Metadata("Next version", List.of("Revised"), List.of("New"))));
        JsonNode second = success(call("POST", "/" + resource + "/versions", owner, revision), 200);
        String next = second.get("versionId").asString();
        assertEquals(resource, second.get("id").asString()); assertNotEquals(initial, next);
        assertEquals(409, call("POST", "/" + resource + "/versions", owner, revision).statusCode());
        JsonNode old = success(call("GET", "/" + resource + "/versions/" + initial, owner, null), 200);
        assertEquals("initial.pdf", old.get("filename").asString()); assertFalse(old.get("current").asBoolean());
        assertFalse(old.has("data"));
        JsonNode page = success(call("GET", "/" + resource + "/versions?limit=1", owner, null), 200);
        assertEquals(next, page.get("items").get(0).get("id").asString()); assertTrue(page.get("hasMore").asBoolean());
        HttpResponse<byte[]> bytes = client.send(request("GET", "/" + resource + "/versions/" + initial + "/content", owner, null), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, bytes.statusCode()); assertArrayEquals(new byte[]{0, -1, 2}, bytes.body());
        assertEquals("application/pdf", bytes.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("attachment; filename*=UTF-8''initial.pdf", bytes.headers().firstValue("Content-Disposition").orElseThrow());
        JsonNode restored = success(call("POST", "/" + resource + "/versions/" + initial + "/restore", owner, new VersionExpectation(UUID.fromString(next))), 200);
        assertEquals("initial.pdf", restored.get("filename").asString()); assertNotEquals(initial, restored.get("versionId").asString());
        share(bucket);
        assertEquals(200, call("GET", "/" + resource + "/versions", reader, null).statusCode());
        assertEquals(404, call("POST", "/" + resource + "/versions", reader, new Revise(UUID.fromString(restored.get("versionId").asString()), revision.upload())).statusCode());
        assertEquals(404, call("GET", "/" + resource + "/versions/" + UUID.randomUUID(), owner, null).statusCode());
        assertEquals(400, call("GET", "/" + resource + "/versions?limit=0", owner, null).statusCode());
    }
}
