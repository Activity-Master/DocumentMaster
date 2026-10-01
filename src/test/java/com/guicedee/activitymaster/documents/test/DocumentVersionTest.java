package com.guicedee.activitymaster.documents.test;

import com.guicedee.activitymaster.documents.*;
import com.guicedee.activitymaster.documents.DocumentModels.*;
import com.guicedee.activitymaster.fsdm.client.services.IResourceItemService;
import com.guicedee.client.IGuiceContext;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

/** Canonical SCD transitions and retained immutable payloads, with no deprecated data-row dependency. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentVersionTest {
    private DocumentTestFixture fixture;
    private IDocumentService service;
    private DocumentIdentity owner, reader, outsider;
    @BeforeAll void setup() {
        fixture = DocumentTestFixture.get(); service = fixture.service;
        owner = identity(fixture.actorId); reader = identity(fixture.recipientId); outsider = identity(fixture.outsiderId);
    }
    private DocumentIdentity identity(UUID party) {
        return new DocumentIdentity(party, fixture.enterpriseId, new ActivityScope.Context(ActivityScope.Realm.WORK, fixture.enterpriseId), fixture.tokenFor(party));
    }
    private Bucket bucket() {
        return fixture.run(c -> service.createBucket(c.getItem1(), c.getItem3(), owner, new CreateBucket("SCD versions", BucketKind.BUCKET)));
    }
    private Upload upload(String name, byte[] bytes) {
        return new Upload(name + ".pdf", "application/pdf", bytes, new Metadata(name, List.of(name), List.of("Saved")));
    }
    private Document document(Bucket bucket) {
        return fixture.run(c -> service.upload(c.getItem1(), c.getItem3(), owner, bucket.id(), upload("Initial", new byte[]{0, 1, -1})));
    }
    private Document revise(Document document, String name, byte[] bytes) {
        return fixture.run(c -> service.revise(c.getItem1(), c.getItem3(), owner, document.id(), new Revise(document.versionId(), upload(name, bytes))));
    }
    private long count(String table) {
        return fixture.run(c -> c.getItem1().createNativeQuery("select count(*) from " + table, Long.class).getSingleResult());
    }
    private Page<Version> versions(Document document, int offset, int limit) {
        return fixture.run(c -> service.versions(c.getItem1(), c.getItem3(), owner, document.id(), offset, limit));
    }
    private Version version(Document document, UUID version) {
        return fixture.run(c -> service.version(c.getItem1(), c.getItem3(), owner, document.id(), version));
    }
    private Content content(Document document, UUID version) {
        return fixture.run(c -> service.downloadVersion(c.getItem1(), c.getItem3(), owner, document.id(), version));
    }

    @Test void versionsBinaryAndMetadataWithContiguousScdRangesAndStableResourceRatings() {
        long deprecatedRows = count("resource.resourceitemdata"), events = count("event.event");
        Document first = document(bucket());
        assertEquals(first.id(), first.versionId());
        fixture.run(c -> service.rate(c.getItem1(), c.getItem3(), owner, first.id(), new Rate(5)));
        Document second = revise(first, "Second", new byte[]{4, 5});
        assertEquals(first.id(), second.id()); assertNotEquals(first.versionId(), second.versionId());
        assertEquals(1, second.ratingCount()); assertEquals(5, second.myRating());
        assertArrayEquals(new byte[]{0, 1, -1}, content(first, first.versionId()).data());
        assertArrayEquals(new byte[]{4, 5}, fixture.run(c -> service.download(c.getItem1(), c.getItem3(), owner, first.id())).data());
        Version old = version(first, first.versionId()), current = version(first, second.versionId());
        assertFalse(old.current()); assertTrue(current.current()); assertEquals(old.effectiveTo(), current.effectiveFrom());
        assertEquals("Initial", old.title()); assertEquals(List.of("Initial"), old.categories());
        assertEquals("Second.pdf", current.filename()); assertEquals("Second", current.title());
        long payloads = count("resource.resourceitemdatavalue");
        Document third = fixture.run(c -> service.updateMetadata(c.getItem1(), c.getItem3(), owner, first.id(), new Metadata("Metadata only", List.of("Changed"), List.of())));
        assertNotEquals(second.versionId(), third.versionId()); assertEquals(payloads, count("resource.resourceitemdatavalue"));
        assertEquals("Second", version(first, second.versionId()).title());
        assertEquals("Metadata only", version(first, third.versionId()).title());
        assertEquals(version(first, second.versionId()).effectiveTo(), version(first, third.versionId()).effectiveFrom());
        assertArrayEquals(new byte[]{4, 5}, content(first, third.versionId()).data());
        assertEquals(3, versions(first, 0, 10).items().size());
        Page<Version> page = versions(first, 0, 1); assertTrue(page.hasMore()); assertEquals(third.versionId(), page.items().getFirst().id());
        assertFalse(versions(first, 2, 1).hasMore());
        assertEquals(deprecatedRows, count("resource.resourceitemdata")); assertEquals(events, count("event.event"));
        assertArrayEquals(new byte[]{4, 5}, fixture.run(c -> {
            IResourceItemService<?> resources = IGuiceContext.get(IResourceItemService.class);
            return resources.findByUUID(c.getItem1(), first.id()).chain(item -> item.getData(c.getItem1(), fixture.token));
        }));
    }

    @Test void rejectsStaleWritersAfterLockingAndDoesNotLeaveOrphanPayloads() {
        Document first = document(bucket());
        long payloads = count("resource.resourceitemdatavalue");
        var a = CompletableFuture.supplyAsync(() -> revise(first, "Writer A", new byte[]{1})).handle((value, error) -> error == null ? value : error);
        var b = CompletableFuture.supplyAsync(() -> revise(first, "Writer B", new byte[]{2})).handle((value, error) -> error == null ? value : error);
        List<Object> results = List.of(a.join(), b.join());
        assertEquals(1, results.stream().filter(Document.class::isInstance).count());
        Throwable failure = (Throwable) results.stream().filter(Throwable.class::isInstance).findFirst().orElseThrow();
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        assertInstanceOf(ClientErrorException.class, failure); assertEquals(409, ((ClientErrorException) failure).getResponse().getStatus());
        assertEquals(payloads + 1, count("resource.resourceitemdatavalue")); assertEquals(2, versions(first, 0, 10).items().size());
    }

    @Test void resourceItemServiceRetainsImmutableValuesAndDoesNotCreateLegacyDataRows() {
        long legacyRows = count("resource.resourceitemdata");
        Document first = document(bucket());
        IResourceItemService<?> resources = IGuiceContext.get(IResourceItemService.class);
        assertThrows(RuntimeException.class, () -> fixture.run(c ->
                resources.storeResourceDataValue(c.getItem1(), first.id(), new byte[]{9})));
        assertArrayEquals(new byte[]{0, 1, -1}, fixture.run(c -> resources.getResourceDataValue(c.getItem1(), first.id())));
        byte[] fetched = fixture.run(c -> resources.getResourceDataValue(c.getItem1(), first.id()));
        fetched[0] = 99;
        assertArrayEquals(new byte[]{0, 1, -1}, content(first, first.versionId()).data());
        assertEquals(legacyRows, count("resource.resourceitemdata"));
    }

    @Test void restoresAsANewVersionAndRollsBackTheWholeTransitionOnFailure() {
        Document first = document(bucket());
        long payloads = count("resource.resourceitemdatavalue");
        assertThrows(IllegalStateException.class, () -> fixture.run(c -> service.revise(c.getItem1(), c.getItem3(), owner, first.id(),
                        new Revise(first.versionId(), upload("Rollback", new byte[]{9})))
                .chain(() -> Uni.createFrom().failure(new IllegalStateException("Force transaction rollback")))));
        assertEquals(payloads, count("resource.resourceitemdatavalue")); assertEquals(1, versions(first, 0, 10).items().size());
        assertTrue(version(first, first.versionId()).current());
        Document second = revise(first, "Changed", new byte[]{3});
        long beforeRestore = count("resource.resourceitemdatavalue");
        Document restored = fixture.run(c -> service.restoreVersion(c.getItem1(), c.getItem3(), owner, first.id(), first.versionId(), new VersionExpectation(second.versionId())));
        assertEquals(first.id(), restored.id()); assertNotEquals(first.versionId(), restored.versionId());
        assertEquals("Initial", restored.title()); assertEquals(first.sha256(), restored.sha256());
        assertArrayEquals(new byte[]{0, 1, -1}, content(first, restored.versionId()).data());
        assertEquals(beforeRestore, count("resource.resourceitemdatavalue")); assertEquals(3, versions(first, 0, 10).items().size());
        assertFalse(version(first, first.versionId()).current()); assertEquals("Changed", version(first, second.versionId()).title());
    }

    @Test void gatesAllHistoryOnCurrentMembershipAndRestrictsWritesToResourceOwner() {
        Bucket bucket = bucket(); Document first = document(bucket); Document second = revise(first, "Private", new byte[]{6});
        fixture.run(c -> service.grant(c.getItem1(), c.getItem3(), owner, bucket.id(), fixture.recipientId, new Grant(Access.EDITOR)));
        assertEquals("Initial", fixture.run(c -> service.version(c.getItem1(), c.getItem3(), reader, first.id(), first.versionId())).title());
        assertThrows(NotFoundException.class, () -> fixture.run(c -> service.revise(c.getItem1(), c.getItem3(), reader, first.id(), new Revise(second.versionId(), upload("Denied", new byte[]{1})))));
        assertThrows(NotFoundException.class, () -> fixture.run(c -> service.restoreVersion(c.getItem1(), c.getItem3(), reader, first.id(), first.versionId(), new VersionExpectation(second.versionId()))));
        assertThrows(NotFoundException.class, () -> fixture.run(c -> service.versions(c.getItem1(), c.getItem3(), outsider, first.id(), 0, 50)));
        Document other = document(bucket());
        assertThrows(NotFoundException.class, () -> version(first, other.versionId()));
        assertThrows(NotFoundException.class, () -> content(first, other.versionId()));
        assertThrows(NotFoundException.class, () -> fixture.run(c -> service.restoreVersion(c.getItem1(), c.getItem3(), owner, first.id(), other.versionId(), new VersionExpectation(second.versionId()))));
        fixture.run(c -> service.revoke(c.getItem1(), c.getItem3(), owner, bucket.id(), fixture.recipientId));
        assertThrows(NotFoundException.class, () -> fixture.run(c -> service.downloadVersion(c.getItem1(), c.getItem3(), reader, first.id(), first.versionId())));
        long retained = count("resource.resourceitemdatavalue");
        fixture.run(c -> service.archive(c.getItem1(), c.getItem3(), owner, first.id()));
        assertThrows(NotFoundException.class, () -> versions(first, 0, 50)); assertThrows(NotFoundException.class, () -> content(first, first.versionId()));
        assertEquals(retained, count("resource.resourceitemdatavalue"));
    }

    @Test void readsMigratedLegacyPayloadDirectlyAndSeedsItsHistoryOnFirstRevision() {
        Document legacy = document(bucket());
        // Simulate an existing installation with a payload but no version classification yet.
        fixture.run(c -> c.getItem1().createNativeQuery("delete from resource.resourceitemxclassification where resourceitemxclassificationid=:version")
                .setParameter("version", legacy.versionId()).executeUpdate());
        Document found = fixture.run(c -> service.find(c.getItem1(), c.getItem3(), owner, legacy.id()));
        assertEquals(legacy.id(), found.versionId());
        assertArrayEquals(new byte[]{0, 1, -1}, content(legacy, legacy.versionId()).data());
        assertEquals(1, versions(legacy, 0, 50).items().size());
        Document revised = revise(found, "Migrated revision", new byte[]{7});
        assertEquals(2, versions(legacy, 0, 50).items().size());
        assertEquals("Initial", version(legacy, legacy.versionId()).title());
        assertEquals("Migrated revision", version(legacy, revised.versionId()).title());
    }
}
