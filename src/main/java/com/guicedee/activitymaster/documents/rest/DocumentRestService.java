package com.guicedee.activitymaster.documents.rest;

import com.google.inject.Inject;
import com.guicedee.activitymaster.documents.DocumentApi;
import com.guicedee.activitymaster.documents.DocumentModels.*;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Path("{enterprise}/documents")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public final class DocumentRestService {
    @Inject private DocumentApi api;
    @POST @Path("buckets") public Uni<Bucket> createBucket(@PathParam("enterprise") String enterprise, CreateBucket request) {
        return api.createBucket(enterprise, request);
    }
    @GET @Path("buckets") public Uni<Page<Bucket>> listBuckets(@PathParam("enterprise") String enterprise,
            @QueryParam("parent") UUID parent, @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return api.listBuckets(enterprise, parent, offset, limit);
    }
    @GET @Path("buckets/{bucket}") public Uni<Bucket> findBucket(@PathParam("enterprise") String enterprise, @PathParam("bucket") UUID bucket) {
        return api.findBucket(enterprise, bucket);
    }
    @GET @Path("buckets/{bucket}/members") public Uni<List<Member>> members(@PathParam("enterprise") String enterprise, @PathParam("bucket") UUID bucket) {
        return api.members(enterprise, bucket);
    }
    @PUT @Path("buckets/{bucket}/members/{party}") public Uni<Void> grant(@PathParam("enterprise") String enterprise,
            @PathParam("bucket") UUID bucket, @PathParam("party") UUID party, Grant request) {
        return api.grant(enterprise, bucket, party, request);
    }
    @DELETE @Path("buckets/{bucket}/members/{party}") public Uni<Void> revoke(@PathParam("enterprise") String enterprise,
            @PathParam("bucket") UUID bucket, @PathParam("party") UUID party) {
        return api.revoke(enterprise, bucket, party);
    }
    @POST @Path("buckets/{bucket}/documents") public Uni<Document> upload(@PathParam("enterprise") String enterprise, @PathParam("bucket") UUID bucket, Upload request) {
        return api.upload(enterprise, bucket, request);
    }
    @GET @Path("buckets/{bucket}/documents") public Uni<Page<Document>> list(@PathParam("enterprise") String enterprise, @PathParam("bucket") UUID bucket,
            @QueryParam("category") String category, @QueryParam("label") String label, @QueryParam("search") String search,
            @QueryParam("offset") @DefaultValue("0") int offset, @QueryParam("limit") @DefaultValue("50") int limit) {
        return api.list(enterprise, bucket, new Filter(category, label, search), offset, limit);
    }
    @PUT @Path("buckets/{bucket}/documents/{resource}") public Uni<Void> addToBucket(@PathParam("enterprise") String enterprise,
            @PathParam("bucket") UUID bucket, @PathParam("resource") UUID resource) {
        return api.addToBucket(enterprise, bucket, resource);
    }
    @DELETE @Path("buckets/{bucket}/documents/{resource}") public Uni<Void> removeFromBucket(@PathParam("enterprise") String enterprise,
            @PathParam("bucket") UUID bucket, @PathParam("resource") UUID resource) {
        return api.removeFromBucket(enterprise, bucket, resource);
    }
    @PUT @Path("buckets/{parent}/children/{child}") public Uni<Void> childBucket(@PathParam("enterprise") String enterprise,
            @PathParam("parent") UUID parent, @PathParam("child") UUID child) {
        return api.childBucket(enterprise, parent, child, false);
    }
    @DELETE @Path("buckets/{parent}/children/{child}") public Uni<Void> removeChild(@PathParam("enterprise") String enterprise,
            @PathParam("parent") UUID parent, @PathParam("child") UUID child) {
        return api.childBucket(enterprise, parent, child, true);
    }
    @PUT @Path("buckets/{bucket}/arrangements/{arrangement}") public Uni<Void> attach(@PathParam("enterprise") String enterprise,
            @PathParam("bucket") UUID bucket, @PathParam("arrangement") UUID arrangement) {
        return api.attachBucket(enterprise, arrangement, bucket, false);
    }
    @DELETE @Path("buckets/{bucket}/arrangements/{arrangement}") public Uni<Void> detach(@PathParam("enterprise") String enterprise,
            @PathParam("bucket") UUID bucket, @PathParam("arrangement") UUID arrangement) {
        return api.attachBucket(enterprise, arrangement, bucket, true);
    }
    @GET @Path("{resource}") public Uni<Document> find(@PathParam("enterprise") String enterprise, @PathParam("resource") UUID resource) {
        return api.find(enterprise, resource);
    }
    @GET @Path("{resource}/content") @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Uni<Response> download(@PathParam("enterprise") String enterprise, @PathParam("resource") UUID resource) {
        return api.download(enterprise, resource).map(DocumentRestService::contentResponse);
    }
    private static Response contentResponse(Content content) {
        return Response.ok(content.data(), content.contentType())
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(content.filename(), StandardCharsets.UTF_8)
                        .replace("+", "%20").replace("*", "%2A"))
                .header("X-Content-Type-Options", "nosniff").header("Cache-Control", "no-store").build();
    }
    @POST @Path("{resource}/versions") public Uni<Document> revise(@PathParam("enterprise") String enterprise, @PathParam("resource") UUID resource, Revise request) {
        return api.revise(enterprise, resource, request);
    }
    @GET @Path("{resource}/versions") public Uni<Page<Version>> versions(@PathParam("enterprise") String enterprise, @PathParam("resource") UUID resource,
            @QueryParam("offset") @DefaultValue("0") int offset, @QueryParam("limit") @DefaultValue("50") int limit) {
        return api.versions(enterprise, resource, offset, limit);
    }
    @GET @Path("{resource}/versions/{version}") public Uni<Version> version(@PathParam("enterprise") String enterprise,
            @PathParam("resource") UUID resource, @PathParam("version") UUID version) {
        return api.version(enterprise, resource, version);
    }
    @GET @Path("{resource}/versions/{version}/content") @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Uni<Response> versionContent(@PathParam("enterprise") String enterprise, @PathParam("resource") UUID resource, @PathParam("version") UUID version) {
        return api.downloadVersion(enterprise, resource, version).map(DocumentRestService::contentResponse);
    }
    @POST @Path("{resource}/versions/{version}/restore") public Uni<Document> restoreVersion(@PathParam("enterprise") String enterprise,
            @PathParam("resource") UUID resource, @PathParam("version") UUID version, VersionExpectation request) {
        return api.restoreVersion(enterprise, resource, version, request);
    }
    @PUT @Path("{resource}/metadata") public Uni<Document> metadata(@PathParam("enterprise") String enterprise, @PathParam("resource") UUID resource, Metadata request) {
        return api.updateMetadata(enterprise, resource, request);
    }
    @PUT @Path("{resource}/rating") public Uni<Document> rate(@PathParam("enterprise") String enterprise, @PathParam("resource") UUID resource, Rate request) {
        return api.rate(enterprise, resource, request);
    }
    @DELETE @Path("{resource}/rating") public Uni<Document> clearRating(@PathParam("enterprise") String enterprise, @PathParam("resource") UUID resource) {
        return api.clearRating(enterprise, resource);
    }
    @DELETE @Path("{resource}") public Uni<Void> archive(@PathParam("enterprise") String enterprise, @PathParam("resource") UUID resource) {
        return api.archive(enterprise, resource);
    }
}
