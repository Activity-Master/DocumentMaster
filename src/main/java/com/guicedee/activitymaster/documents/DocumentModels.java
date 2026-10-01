package com.guicedee.activitymaster.documents;

import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;

/** Transport contracts. IDs identify targets; the host identity supplies authority. */
public final class DocumentModels {
    private DocumentModels() { }
    public enum BucketKind { BUCKET, CATEGORY, GROUP }
    public enum Access { READER, EDITOR }
    public record CreateBucket(String name, BucketKind kind) { }
    public record Bucket(UUID id, String name, BucketKind kind, ActivityScope.Realm realm, UUID ownerId) { }
    public record Member(UUID partyId, String access) { }
    public record Grant(Access access) { }
    public record Metadata(String title, List<String> categories, List<String> labels) {
        public Metadata {
            categories = categories == null ? List.of() : List.copyOf(categories);
            labels = labels == null ? List.of() : List.copyOf(labels);
        }
    }
    public record Upload(String filename, String contentType, byte[] data, Metadata metadata) {
        public Upload { data = data == null ? null : data.clone(); }
        @Override public byte[] data() { return data == null ? null : data.clone(); }
    }
    public record Revise(UUID expectedVersionId, Upload upload) { }
    public record VersionExpectation(UUID expectedVersionId) { }
    public record Document(UUID id, String resourceType, String filename, String contentType,
                           long size, String sha256, String title, List<String> categories,
                           List<String> labels, double ratingAverage, long ratingCount, Integer myRating, UUID versionId) {
        public Document { categories = List.copyOf(categories); labels = List.copyOf(labels); }
    }
    /** An SCD classification-row ID identifies a revision of the stable resource. Ratings remain resource-level. */
    public record Version(UUID id, UUID resourceId, String resourceType, OffsetDateTime effectiveFrom,
                          OffsetDateTime effectiveTo, boolean current, String filename, String contentType,
                          long size, String sha256, String title, List<String> categories, List<String> labels) {
        public Version { categories = List.copyOf(categories); labels = List.copyOf(labels); }
    }
    public record Content(String filename, String contentType, byte[] data) {
        public Content { data = data.clone(); }
        @Override public byte[] data() { return data.clone(); }
    }
    public record Rate(int score) { }
    public record Filter(String category, String label, String search) { }
    public record Page<T>(List<T> items, int offset, int limit, boolean hasMore) {
        public Page { items = List.copyOf(items); }
    }
}
