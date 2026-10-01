package com.guicedee.activitymaster.documents;

import com.guicedee.activitymaster.documents.DocumentModels.*;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import java.util.List;
import java.util.UUID;

/** Caller-owned stateless transactions; identity must come from a verified host. */
public interface IDocumentService {
    Uni<Bucket> createBucket(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, CreateBucket request);
    Uni<Bucket> findBucket(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID bucket);
    Uni<Page<Bucket>> listBuckets(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID parent, int offset, int limit);
    Uni<List<Member>> members(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID bucket);
    Uni<Void> grant(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID bucket, UUID party, Grant request);
    Uni<Void> revoke(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID bucket, UUID party);
    Uni<Document> upload(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID bucket, Upload request);
    Uni<Document> find(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource);
    Uni<Page<Document>> list(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID bucket, Filter filter, int offset, int limit);
    Uni<Content> download(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource);
    Uni<Document> revise(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource, Revise request);
    Uni<Page<Version>> versions(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource, int offset, int limit);
    Uni<Version> version(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource, UUID version);
    Uni<Content> downloadVersion(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource, UUID version);
    Uni<Document> restoreVersion(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource, UUID version, VersionExpectation request);
    Uni<Document> updateMetadata(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource, Metadata request);
    Uni<Void> addToBucket(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID bucket, UUID resource);
    Uni<Void> removeFromBucket(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID bucket, UUID resource);
    Uni<Void> archive(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource);
    Uni<Document> rate(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource, Rate request);
    Uni<Document> clearRating(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource);
    Uni<Void> childBucket(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID parent, UUID child, boolean remove);
    Uni<Void> attachBucket(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID arrangement, UUID bucket, boolean remove);
}
