package com.guicedee.activitymaster.documents.test;

import com.guicedee.activitymaster.documents.*;
import com.guicedee.activitymaster.documents.DocumentModels.*;
import com.guicedee.activitymaster.fsdm.client.services.*;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import com.guicedee.client.IGuiceContext;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple4;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentStorageTest {
    private IDocumentService service;
    private UUID enterpriseId;
    private UUID actorId;
    private UUID recipientId;
    private UUID outsiderId;
    private UUID token;

    private DocumentTestFixture fixture;
    @Test void pluginRemovalAndAdministratorDenialRecheckExistingDocuments() {
        assertFalse(com.guicedee.activitymaster.fsdm.client.services.systems.IMasterSystem.class
                .isAssignableFrom(DocumentSystem.class));
        var user = run(c -> PluginTestFixture.user(c.getItem1(), c.getItem2()));
        var identity = new DocumentIdentity(user.partyId(), user.enterpriseId(),
                new ActivityScope.Context(ActivityScope.Realm.WORK, user.enterpriseId()), user.identityToken());
        assertThrows(SecurityException.class, () -> run(c -> service.listBuckets(c.getItem1(), c.getItem3(), identity, null, 0, 10)));
        run(c -> PluginTestFixture.enable(c.getItem1(), c.getItem3(), user));
        Bucket bucket = bucket(identity, "Plugin authority");
        Document document = upload(identity, bucket, "Private plugin document");
        run(c -> IGuiceContext.get(com.guicedee.activitymaster.fsdm.plugins.PluginService.class)
                .remove(c.getItem1(), c.getItem3(), user, c.getItem3().getId(), user.partyId()));
        assertThrows(SecurityException.class, () -> run(c -> service.download(c.getItem1(), c.getItem3(), identity, document.id())));
        run(c -> IGuiceContext.get(com.guicedee.activitymaster.fsdm.plugins.PluginService.class)
                .install(c.getItem1(), c.getItem3(), user, c.getItem3().getId(), user.partyId()));
        assertThrows(SecurityException.class, () -> run(c -> service.versions(c.getItem1(), c.getItem3(), identity, document.id(), 0, 10)));
        run(c -> PluginTestFixture.enable(c.getItem1(), c.getItem3(), user));
        UUID coreId = run(c -> {
            ISystemsService<?> systems = IGuiceContext.get(ISystemsService.class);
            return systems.getActivityMaster(c.getItem1(), c.getItem2()).map(core -> core.getId());
        });
        try {
            run(c -> IGuiceContext.get(com.guicedee.activitymaster.fsdm.plugins.PluginService.class)
                    .setSystemAccess(c.getItem1(), c.getItem3(), user, c.getItem3().getId(), coreId, null, false));
            assertThrows(SecurityException.class, () -> run(c -> service.version(c.getItem1(), c.getItem3(), identity, document.id(), document.versionId())));
        } finally {
            run(c -> IGuiceContext.get(com.guicedee.activitymaster.fsdm.plugins.PluginService.class)
                    .setSystemAccess(c.getItem1(), c.getItem3(), user, c.getItem3().getId(), coreId, null, true));
        }
        assertNotNull(run(c -> service.download(c.getItem1(), c.getItem3(), identity, document.id())));
    }
    @BeforeAll void setup() {
        fixture = DocumentTestFixture.get();
        service = fixture.service;
        enterpriseId = fixture.enterpriseId;
        actorId = fixture.actorId;
        recipientId = fixture.recipientId;
        outsiderId = fixture.outsiderId;
        token = fixture.token;
    }

    private <T> T run(Function<Tuple4<Mutiny.StatelessSession,
            com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise<?, ?>,
            com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems<?, ?>, UUID[]>, Uni<T>> work) {
        return fixture.run(work);
    }

    private DocumentIdentity identity(UUID party) {
        return new DocumentIdentity(party, enterpriseId,
                new ActivityScope.Context(ActivityScope.Realm.WORK, enterpriseId), fixture.tokenFor(party));
    }

    private DocumentIdentity social(UUID party) {
        return new DocumentIdentity(party, enterpriseId,
                new ActivityScope.Context(ActivityScope.Realm.SOCIAL, party), fixture.tokenFor(party));
    }

    private Bucket bucket(DocumentIdentity identity, String name) {
        return run(c -> service.createBucket(c.getItem1(), c.getItem3(), identity, new CreateBucket(name, BucketKind.BUCKET)));
    }
    private Document upload(DocumentIdentity identity, Bucket bucket, String title) {
        return run(c -> service.upload(c.getItem1(), c.getItem3(), identity, bucket.id(),
                new Upload("invoice.pdf", "application/pdf", new byte[]{0, 1, -1, 2},
                        new Metadata(title, List.of("Invoices"), List.of("Reviewed", "Reviewed")))));
    }
    private long count(String table) {
        return run(c -> c.getItem1().createNativeQuery("select count(*) from " + table, Long.class).getSingleResult());
    }

    @Test void storesTypedResourcesPayloadAndMetadataInCanonicalDomains() {
        Bucket bucket = bucket(identity(actorId), "Accounts");
        long before = count("event.event");
        Document doc = upload(identity(actorId), bucket, "September invoice");
        assertEquals("Document", doc.resourceType());
        assertEquals(List.of("Invoices"), doc.categories());
        assertEquals(List.of("Reviewed"), doc.labels());
        assertEquals(4, doc.size());
        assertEquals(64, doc.sha256().length());
        assertEquals(0, doc.ratingCount());
        assertEquals(doc, run(c -> service.find(c.getItem1(), c.getItem3(), identity(actorId), doc.id())));
        assertArrayEquals(new byte[]{0, 1, -1, 2}, run(c -> service.download(c.getItem1(), c.getItem3(), identity(actorId), doc.id())).data());
        assertEquals(1L, (long) run(c -> c.getItem1().createNativeQuery("select count(*) from resource.resourceitem r"
                        + " join resource.resourceitemxclassification d on d.resourceitemid=r.resourceitemid"
                        + " join classification.classification vc on vc.classificationid=d.classificationid and vc.classificationname='DocumentVersion'"
                        + " join resource.resourceitemdatavalue v on v.resourceitemdatavalueid=cast(d.value as uuid)"
                        + " join resource.resourceitemxresourceitemtype rt on rt.resourceitemid=r.resourceitemid"
                        + " join resource.resourceitemtype t on t.resourceitemtypeid=rt.resourceitemtypeid"
                        + " where r.resourceitemid=:id and t.resourceitemtypename='Document'", Long.class)
                .setParameter("id", doc.id()).getSingleResult()));
        assertEquals(before, count("event.event"));
    }

    @Test void categorizesFiltersPagesAndPreservesMetadataHistory() {
        Bucket bucket = bucket(identity(actorId), "Filtered");
        Document a = upload(identity(actorId), bucket, "Invoice A");
        upload(identity(actorId), bucket, "Invoice B");
        var first = run(c -> service.list(c.getItem1(), c.getItem3(), identity(actorId), bucket.id(), new Filter("Invoices", "Reviewed", "INVOICE"), 0, 1));
        assertTrue(first.hasMore());
        assertEquals(a.id(), first.items().getFirst().id());
        assertFalse(run(c -> service.list(c.getItem1(), c.getItem3(), identity(actorId), bucket.id(), null, 1, 1)).hasMore());
        Document updated = run(c -> service.updateMetadata(c.getItem1(), c.getItem3(), identity(actorId), a.id(),
                new Metadata("Receipt A", List.of("Receipts"), List.of("Paid"))));
        assertEquals(List.of("Receipts"), updated.categories());
        assertEquals(List.of("Paid"), updated.labels());
        assertTrue(run(c -> service.list(c.getItem1(), c.getItem3(), identity(actorId), bucket.id(), new Filter("Invoices", "Paid", null), 0, 10)).items().isEmpty());
        assertEquals(a.id(), run(c -> service.list(c.getItem1(), c.getItem3(), identity(actorId), bucket.id(), new Filter("Receipts", "Paid", "receipt"), 0, 10)).items().getFirst().id());
        assertEquals(3L, (long) run(c -> c.getItem1().createNativeQuery("select count(*) from resource.resourceitemxclassification x"
                        + " join classification.classification c on c.classificationid=x.classificationid"
                        + " where x.resourceitemid=:id and x.effectivetodate<=statement_timestamp()"
                        + " and c.classificationname in ('DocumentTitle','DocumentCategory','DocumentLabel')", Long.class)
                .setParameter("id", a.id()).getSingleResult()));
    }

    @Test void membershipControlsAccessAndOwnerControlsChanges() {
        Bucket bucket = bucket(identity(actorId), "Shared");
        Document doc = upload(identity(actorId), bucket, "Restricted");
        assertThrows(NotFoundException.class, () -> run(c -> service.download(c.getItem1(), c.getItem3(), identity(outsiderId), doc.id())));
        run(c -> service.grant(c.getItem1(), c.getItem3(), identity(actorId), bucket.id(), recipientId, new Grant(Access.READER)));
        assertEquals(doc.id(), run(c -> service.find(c.getItem1(), c.getItem3(), identity(recipientId), doc.id())).id());
        assertThrows(NotFoundException.class, () -> upload(identity(recipientId), bucket, "Denied"));
        assertThrows(NotFoundException.class, () -> run(c -> service.updateMetadata(c.getItem1(), c.getItem3(), identity(recipientId), doc.id(), new Metadata("Denied", List.of(), List.of()))));
        assertThrows(NotFoundException.class, () -> run(c -> service.grant(c.getItem1(), c.getItem3(), identity(recipientId), bucket.id(), outsiderId, new Grant(Access.EDITOR))));
        assertThrows(BadRequestException.class, () -> run(c -> service.revoke(c.getItem1(), c.getItem3(), identity(actorId), bucket.id(), actorId)));
        run(c -> service.grant(c.getItem1(), c.getItem3(), identity(actorId), bucket.id(), recipientId, new Grant(Access.EDITOR)));
        assertNotNull(upload(identity(recipientId), bucket, "Editor upload"));
        run(c -> service.revoke(c.getItem1(), c.getItem3(), identity(actorId), bucket.id(), recipientId));
        assertThrows(NotFoundException.class, () -> run(c -> service.find(c.getItem1(), c.getItem3(), identity(recipientId), doc.id())));
        run(c -> service.grant(c.getItem1(), c.getItem3(), identity(actorId), bucket.id(), recipientId, new Grant(Access.READER)));
        assertNotNull(run(c -> service.download(c.getItem1(), c.getItem3(), identity(recipientId), doc.id())));
    }

    @Test void storesPartyRatingsAndAtomicResourceSummariesWithoutEvents() {
        Bucket bucket = bucket(identity(actorId), "Ratings");
        Document doc = upload(identity(actorId), bucket, "Rated");
        run(c -> service.grant(c.getItem1(), c.getItem3(), identity(actorId), bucket.id(), recipientId, new Grant(Access.READER)));
        long events = count("event.event");
        Document first = run(c -> service.rate(c.getItem1(), c.getItem3(), identity(actorId), doc.id(), new Rate(5)));
        assertEquals(5, first.myRating()); assertEquals(1, first.ratingCount()); assertEquals(5, first.ratingAverage());
        Document second = run(c -> service.rate(c.getItem1(), c.getItem3(), identity(recipientId), doc.id(), new Rate(3)));
        assertEquals(3, second.myRating()); assertEquals(2, second.ratingCount()); assertEquals(4, second.ratingAverage());
        Document repeat = run(c -> service.rate(c.getItem1(), c.getItem3(), identity(recipientId), doc.id(), new Rate(3)));
        assertEquals(second, repeat);
        Document changed = run(c -> service.rate(c.getItem1(), c.getItem3(), identity(actorId), doc.id(), new Rate(1)));
        assertEquals(2, changed.ratingCount()); assertEquals(2, changed.ratingAverage());
        assertEquals(3, run(c -> service.find(c.getItem1(), c.getItem3(), identity(recipientId), doc.id())).myRating());
        Document cleared = run(c -> service.clearRating(c.getItem1(), c.getItem3(), identity(recipientId), doc.id()));
        assertNull(cleared.myRating()); assertEquals(1, cleared.ratingCount()); assertEquals(1, cleared.ratingAverage());
        run(c -> service.clearRating(c.getItem1(), c.getItem3(), identity(actorId), doc.id()));
        Document empty = run(c -> service.clearRating(c.getItem1(), c.getItem3(), identity(actorId), doc.id()));
        assertEquals(0, empty.ratingAverage()); assertEquals(0, empty.ratingCount());
        assertEquals(events, count("event.event"));
        assertThrows(BadRequestException.class, () -> run(c -> service.rate(c.getItem1(), c.getItem3(), identity(actorId), doc.id(), new Rate(6))));
        assertThrows(NotFoundException.class, () -> run(c -> service.rate(c.getItem1(), c.getItem3(), identity(outsiderId), doc.id(), new Rate(5))));
    }

    @Test void serializesConcurrentRatingsWithoutLosingCountOrSum() {
        Bucket bucket = bucket(identity(actorId), "Concurrent");
        Document doc = upload(identity(actorId), bucket, "Concurrent rating");
        run(c -> service.grant(c.getItem1(), c.getItem3(), identity(actorId), bucket.id(), recipientId, new Grant(Access.READER)));
        for (int iteration = 0; iteration < 3; iteration++) {
            var a = CompletableFuture.supplyAsync(() -> run(c -> service.rate(c.getItem1(), c.getItem3(), identity(actorId), doc.id(), new Rate(5))));
            var b = CompletableFuture.supplyAsync(() -> run(c -> service.rate(c.getItem1(), c.getItem3(), identity(recipientId), doc.id(), new Rate(1))));
            CompletableFuture.allOf(a, b).join();
            Document rated = run(c -> service.find(c.getItem1(), c.getItem3(), identity(actorId), doc.id()));
            assertEquals(2, rated.ratingCount()); assertEquals(3, rated.ratingAverage());
            run(c -> service.clearRating(c.getItem1(), c.getItem3(), identity(actorId), doc.id()));
            run(c -> service.clearRating(c.getItem1(), c.getItem3(), identity(recipientId), doc.id()));
        }
    }

    @Test void nestsBucketsRejectsCyclesAndDoesNotInheritMembership() {
        Bucket a = bucket(identity(actorId), "Parent"), b = bucket(identity(actorId), "Child"), d = bucket(identity(actorId), "Grandchild");
        run(c -> service.childBucket(c.getItem1(), c.getItem3(), identity(actorId), a.id(), b.id(), false));
        run(c -> service.childBucket(c.getItem1(), c.getItem3(), identity(actorId), b.id(), d.id(), false));
        run(c -> service.childBucket(c.getItem1(), c.getItem3(), identity(actorId), a.id(), b.id(), false));
        assertEquals(List.of(b.id()), run(c -> service.listBuckets(c.getItem1(), c.getItem3(), identity(actorId), a.id(), 0, 10)).items().stream().map(Bucket::id).toList());
        assertThrows(BadRequestException.class, () -> run(c -> service.childBucket(c.getItem1(), c.getItem3(), identity(actorId), d.id(), a.id(), false)));
        run(c -> service.grant(c.getItem1(), c.getItem3(), identity(actorId), a.id(), recipientId, new Grant(Access.READER)));
        assertTrue(run(c -> service.listBuckets(c.getItem1(), c.getItem3(), identity(recipientId), a.id(), 0, 10)).items().isEmpty());
        assertThrows(NotFoundException.class, () -> run(c -> service.findBucket(c.getItem1(), c.getItem3(), identity(recipientId), b.id())));
        run(c -> service.childBucket(c.getItem1(), c.getItem3(), identity(actorId), a.id(), b.id(), true));
        assertTrue(run(c -> service.listBuckets(c.getItem1(), c.getItem3(), identity(actorId), a.id(), 0, 10)).items().isEmpty());
    }

    @Test void linksDocumentsAcrossBucketsAndArchivesWithoutDeletingBinaryHistory() {
        Bucket a = bucket(identity(actorId), "First"), b = bucket(identity(actorId), "Second");
        Document doc = upload(identity(actorId), a, "Multi bucket");
        assertThrows(BadRequestException.class, () -> run(c -> service.removeFromBucket(c.getItem1(), c.getItem3(), identity(actorId), a.id(), doc.id())));
        run(c -> service.addToBucket(c.getItem1(), c.getItem3(), identity(actorId), b.id(), doc.id()));
        run(c -> service.addToBucket(c.getItem1(), c.getItem3(), identity(actorId), b.id(), doc.id()));
        assertEquals(1, run(c -> service.list(c.getItem1(), c.getItem3(), identity(actorId), b.id(), null, 0, 10)).items().size());
        run(c -> service.removeFromBucket(c.getItem1(), c.getItem3(), identity(actorId), a.id(), doc.id()));
        assertTrue(run(c -> service.list(c.getItem1(), c.getItem3(), identity(actorId), a.id(), null, 0, 10)).items().isEmpty());
        long binaries = count("resource.resourceitemdatavalue");
        run(c -> service.archive(c.getItem1(), c.getItem3(), identity(actorId), doc.id()));
        assertThrows(NotFoundException.class, () -> run(c -> service.download(c.getItem1(), c.getItem3(), identity(actorId), doc.id())));
        assertEquals(binaries, count("resource.resourceitemdatavalue"));
        assertTrue(run(c -> service.list(c.getItem1(), c.getItem3(), identity(actorId), b.id(), null, 0, 10)).items().isEmpty());
    }

    @Test void scopesPersonalSocialAndWorkAndRejectsInvalidUploads() {
        DocumentIdentity personal = new DocumentIdentity(actorId, enterpriseId, new ActivityScope.Context(ActivityScope.Realm.PERSONAL, actorId), token);
        Bucket privateBucket = bucket(personal, "Personal");
        assertThrows(BadRequestException.class, () -> run(c -> service.grant(c.getItem1(), c.getItem3(), personal, privateBucket.id(), recipientId, new Grant(Access.READER))));
        Bucket socialBucket = bucket(social(actorId), "Social");
        run(c -> service.grant(c.getItem1(), c.getItem3(), social(actorId), socialBucket.id(), recipientId, new Grant(Access.READER)));
        assertEquals(actorId, run(c -> service.findBucket(c.getItem1(), c.getItem3(), social(recipientId), socialBucket.id())).ownerId());
        Document doc = upload(social(actorId), socialBucket, "Social document");
        assertNotNull(run(c -> service.find(c.getItem1(), c.getItem3(), social(recipientId), doc.id())));
        assertThrows(NotFoundException.class, () -> run(c -> service.find(c.getItem1(), c.getItem3(), identity(actorId), doc.id())));
        assertThrows(SecurityException.class, () -> new DocumentIdentity(recipientId, enterpriseId, new ActivityScope.Context(ActivityScope.Realm.SOCIAL, actorId), token));
        assertThrows(SecurityException.class, () -> run(c -> service.find(c.getItem1(), c.getItem3(), new DocumentIdentity(actorId, enterpriseId,
                new ActivityScope.Context(ActivityScope.Realm.SOCIAL, actorId), UUID.randomUUID()), doc.id())));
        assertThrows(BadRequestException.class, () -> run(c -> service.upload(c.getItem1(), c.getItem3(), social(actorId), socialBucket.id(),
                new Upload("../bad.pdf", "application/pdf", new byte[0], new Metadata("Invalid", List.of(), List.of())))));
        assertThrows(BadRequestException.class, () -> run(c -> service.upload(c.getItem1(), c.getItem3(), social(actorId), socialBucket.id(),
                new Upload("bad.pdf", "application/pdf\r\n", new byte[0], new Metadata("Invalid", List.of(), List.of())))));
        assertThrows(BadRequestException.class, () -> run(c -> service.upload(c.getItem1(), c.getItem3(), social(actorId), socialBucket.id(),
                new Upload("bad.pdf", "application/pdf", new byte[DocumentService.MAX_BYTES + 1], new Metadata("Invalid", List.of(), List.of())))));
    }

    @Test void failedComposedWriteRollsBackPayloadAndResources() {
        Bucket bucket = bucket(identity(actorId), "Rollback");
        long resources = count("resource.resourceitem"), binaries = count("resource.resourceitemdatavalue");
        assertThrows(IllegalStateException.class, () -> run(c -> service.upload(c.getItem1(), c.getItem3(), identity(actorId), bucket.id(),
                        new Upload("rollback.pdf", "application/pdf", new byte[]{1}, new Metadata("Rollback", List.of(), List.of())))
                .chain(() -> Uni.createFrom().failure(new IllegalStateException("Force rollback after payload write")))));
        assertEquals(resources, count("resource.resourceitem")); assertEquals(binaries, count("resource.resourceitemdatavalue"));
    }

    @Test void attachesBucketsThroughArrangementsAndRequiresTargetRowAuthority() {
        Bucket bucket = bucket(identity(actorId), "Attachments");
        UUID arrangement = run(c -> {
            IArrangementsService<?> arrangements = IGuiceContext.get(IArrangementsService.class);
            return arrangements.createArrangementType(c.getItem1(), "Document Attachment Target", c.getItem3(), token)
                    .chain(() -> arrangements.create(c.getItem1(), "Document Attachment Target", UUID.randomUUID(),
                            "DocumentBucketType", "1", c.getItem3(), token)).map(a -> a.getId());
        });
        run(c -> service.attachBucket(c.getItem1(), c.getItem3(), identity(actorId), arrangement, bucket.id(), false));
        run(c -> service.attachBucket(c.getItem1(), c.getItem3(), identity(actorId), arrangement, bucket.id(), false));
        assertEquals(1L, (long) run(c -> c.getItem1().createNativeQuery("select count(*) from arrangement.arrangementxarrangement x"
                        + " join classification.classification cl on cl.classificationid=x.classificationid"
                        + " where x.parentarrangementid=:parent and x.childarrangementid=:child"
                        + " and cl.classificationname='DocumentBucketAttachment' and x.effectivetodate>statement_timestamp()", Long.class)
                .setParameter("parent", arrangement).setParameter("child", bucket.id()).getSingleResult()));
        assertThrows(NotFoundException.class, () -> run(c -> service.attachBucket(c.getItem1(), c.getItem3(), identity(outsiderId), arrangement, bucket.id(), false)));
        assertThrows(NotFoundException.class, () -> run(c -> c.getItem1().createNativeQuery("update arrangement.arrangementsecuritytoken set updateallowed=0 where arrangementid=:id")
                .setParameter("id", arrangement).executeUpdate().chain(() -> service.attachBucket(c.getItem1(), c.getItem3(), identity(actorId), arrangement, bucket.id(), true))));
        run(c -> service.attachBucket(c.getItem1(), c.getItem3(), identity(actorId), arrangement, bucket.id(), true));
    }

    @Test void rechecksLiveSystemGrantsAndRejectsExpiredOrWrongResourceTypes() {
        Bucket bucket = bucket(identity(actorId), "Grant checks");
        Document doc = upload(identity(actorId), bucket, "Protected");
        assertThrows(SecurityException.class, () -> run(c -> c.getItem1().createNativeQuery("update dbo.systemssecuritytoken set updateallowed=0 where systemid=:id")
                .setParameter("id", c.getItem3().getId()).executeUpdate()
                .chain(() -> service.rate(c.getItem1(), c.getItem3(), identity(actorId), doc.id(), new Rate(5)))));
        assertThrows(SecurityException.class, () -> run(c -> c.getItem1().createNativeQuery("update dbo.systemssecuritytoken set readallowed=0 where systemid=:id")
                .setParameter("id", c.getItem3().getId()).executeUpdate()
                .chain(() -> service.download(c.getItem1(), c.getItem3(), identity(actorId), doc.id()))));
        assertThrows(NotFoundException.class, () -> run(c -> c.getItem1().createNativeQuery("update resource.resourceitem set effectivetodate=statement_timestamp() where resourceitemid=:id")
                .setParameter("id", doc.id()).executeUpdate()
                .chain(() -> service.download(c.getItem1(), c.getItem3(), identity(actorId), doc.id()))));
        assertThrows(NotFoundException.class, () -> run(c -> c.getItem1().createNativeQuery("update resource.resourceitemxresourceitemtype set effectivetodate=statement_timestamp() where resourceitemid=:id")
                .setParameter("id", doc.id()).executeUpdate()
                .chain(() -> service.find(c.getItem1(), c.getItem3(), identity(actorId), doc.id()))));
        assertEquals(0, run(c -> service.find(c.getItem1(), c.getItem3(), identity(actorId), doc.id())).ratingCount());
    }
}

