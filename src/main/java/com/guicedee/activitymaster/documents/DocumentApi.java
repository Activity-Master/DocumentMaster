package com.guicedee.activitymaster.documents;

import com.guicedee.activitymaster.documents.DocumentModels.*;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import java.util.List;
import java.util.UUID;

import com.google.inject.Inject;
import com.guicedee.activitymaster.fsdm.client.services.SessionUtils;

/** Captures the host identity once and binds it to the entire FSDM transaction. */
public final class DocumentApi {
    private final DocumentIdentityProvider identities;
    private final IDocumentService service;
    @Inject public DocumentApi(DocumentIdentityProvider identities, IDocumentService service) {
        this.identities = identities;
        this.service = service;
    }
    @FunctionalInterface private interface Work<T> {
        Uni<T> run(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity);
    }
    private <T> Uni<T> execute(String enterprise, Work<T> work) {
        if (enterprise == null || enterprise.isBlank()) return Uni.createFrom().failure(new IllegalArgumentException("Enterprise required"));
        return Uni.createFrom().deferred(identities::current)
                .onItem().ifNull().failWith(() -> new SecurityException("Authenticated document identity required"))
                .chain(identity -> SessionUtils.withActivityMaster(enterprise, DocumentSystem.NAME, tuple -> {
                    if (!identity.enterpriseId().equals(tuple.getItem2().getId()))
                        return Uni.createFrom().failure(new SecurityException("Document enterprise scope mismatch"));
                    return work.run(tuple.getItem1(), tuple.getItem3(), identity);
                }));
    }
    public Uni<Bucket> createBucket(String enterprise, CreateBucket request) {
        return execute(enterprise, (session, system, identity) -> service.createBucket(session, system, identity, request));
    }
    public Uni<Bucket> findBucket(String enterprise, UUID bucket) {
        return execute(enterprise, (session, system, identity) -> service.findBucket(session, system, identity, bucket));
    }
    public Uni<Page<Bucket>> listBuckets(String enterprise, UUID parent, int offset, int limit) {
        return execute(enterprise, (session, system, identity) -> service.listBuckets(session, system, identity, parent, offset, limit));
    }
    public Uni<List<Member>> members(String enterprise, UUID bucket) {
        return execute(enterprise, (session, system, identity) -> service.members(session, system, identity, bucket));
    }
    public Uni<Void> grant(String enterprise, UUID bucket, UUID party, Grant request) {
        return execute(enterprise, (session, system, identity) -> service.grant(session, system, identity, bucket, party, request));
    }
    public Uni<Void> revoke(String enterprise, UUID bucket, UUID party) {
        return execute(enterprise, (session, system, identity) -> service.revoke(session, system, identity, bucket, party));
    }
    public Uni<Document> upload(String enterprise, UUID bucket, Upload request) {
        return execute(enterprise, (session, system, identity) -> service.upload(session, system, identity, bucket, request));
    }
    public Uni<Document> find(String enterprise, UUID resource) {
        return execute(enterprise, (session, system, identity) -> service.find(session, system, identity, resource));
    }
    public Uni<Page<Document>> list(String enterprise, UUID bucket, Filter filter, int offset, int limit) {
        return execute(enterprise, (session, system, identity) -> service.list(session, system, identity, bucket, filter, offset, limit));
    }
    public Uni<Content> download(String enterprise, UUID resource) {
        return execute(enterprise, (session, system, identity) -> service.download(session, system, identity, resource));
    }
    public Uni<Document> revise(String enterprise, UUID resource, Revise request) {
        return execute(enterprise, (session, system, identity) -> service.revise(session, system, identity, resource, request));
    }
    public Uni<Page<Version>> versions(String enterprise, UUID resource, int offset, int limit) {
        return execute(enterprise, (session, system, identity) -> service.versions(session, system, identity, resource, offset, limit));
    }
    public Uni<Version> version(String enterprise, UUID resource, UUID version) {
        return execute(enterprise, (session, system, identity) -> service.version(session, system, identity, resource, version));
    }
    public Uni<Content> downloadVersion(String enterprise, UUID resource, UUID version) {
        return execute(enterprise, (session, system, identity) -> service.downloadVersion(session, system, identity, resource, version));
    }
    public Uni<Document> restoreVersion(String enterprise, UUID resource, UUID version, VersionExpectation request) {
        return execute(enterprise, (session, system, identity) -> service.restoreVersion(session, system, identity, resource, version, request));
    }
    public Uni<Document> updateMetadata(String enterprise, UUID resource, Metadata request) {
        return execute(enterprise, (session, system, identity) -> service.updateMetadata(session, system, identity, resource, request));
    }
    public Uni<Void> addToBucket(String enterprise, UUID bucket, UUID resource) {
        return execute(enterprise, (session, system, identity) -> service.addToBucket(session, system, identity, bucket, resource));
    }
    public Uni<Void> removeFromBucket(String enterprise, UUID bucket, UUID resource) {
        return execute(enterprise, (session, system, identity) -> service.removeFromBucket(session, system, identity, bucket, resource));
    }
    public Uni<Void> archive(String enterprise, UUID resource) {
        return execute(enterprise, (session, system, identity) -> service.archive(session, system, identity, resource));
    }
    public Uni<Document> rate(String enterprise, UUID resource, Rate request) {
        return execute(enterprise, (session, system, identity) -> service.rate(session, system, identity, resource, request));
    }
    public Uni<Document> clearRating(String enterprise, UUID resource) {
        return execute(enterprise, (session, system, identity) -> service.clearRating(session, system, identity, resource));
    }
    public Uni<Void> childBucket(String enterprise, UUID parent, UUID child, boolean remove) {
        return execute(enterprise, (session, system, identity) -> service.childBucket(session, system, identity, parent, child, remove));
    }
    public Uni<Void> attachBucket(String enterprise, UUID arrangement, UUID bucket, boolean remove) {
        return execute(enterprise, (session, system, identity) -> service.attachBucket(session, system, identity, arrangement, bucket, remove));
    }
}
