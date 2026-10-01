package com.guicedee.activitymaster.documents.graphql;

import com.guicedee.activitymaster.documents.DocumentApi;
import com.guicedee.activitymaster.documents.DocumentService;
import com.guicedee.client.IGuiceContext;
import com.guicedee.client.scopes.CallScopeProperties;
import com.guicedee.client.scopes.CallScopeSource;
import com.guicedee.client.scopes.CallScoper;
import com.guicedee.vertx.graphql.services.IGraphQLSchemaProvider;
import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ClientErrorException;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.guicedee.activitymaster.documents.DocumentModels.*;

/** Typed GraphQL adapter; all persistence and authority are owned by DocumentApi. */
public final class DocumentGraphQLSchemaProvider implements IGraphQLSchemaProvider<DocumentGraphQLSchemaProvider> {
    private static final Logger LOG = Logger.getLogger(DocumentGraphQLSchemaProvider.class.getName());
    private final DocumentApi suppliedApi;
    public DocumentGraphQLSchemaProvider() { suppliedApi = null; }
    public DocumentGraphQLSchemaProvider(DocumentApi api) { suppliedApi = api; }
    private DocumentApi api() { return suppliedApi == null ? IGuiceContext.get(DocumentApi.class) : suppliedApi; }

    private static final String SDL = """
        # Signed 64-bit integer, serialized as a JSON number.
        scalar DocumentLong
        enum DocumentBucketKind { BUCKET CATEGORY GROUP }
        enum DocumentRealm { PERSONAL SOCIAL WORK }
        enum DocumentAccess { READER EDITOR }
        enum DocumentMemberAccess { OWNER READER EDITOR }
        type DocumentBucket { id: ID!, name: String!, kind: DocumentBucketKind!, realm: DocumentRealm!, ownerId: ID! }
        type DocumentMember { partyId: ID!, access: DocumentMemberAccess! }
        type DocumentResource {
            id: ID!, resourceType: String!, filename: String!, contentType: String!, size: DocumentLong!, sha256: String!,
            title: String!, categories: [String!]!, labels: [String!]!, ratingAverage: Float!, ratingCount: DocumentLong!, myRating: Int, versionId: ID!
        }
        type DocumentVersion {
            id: ID!, resourceId: ID!, resourceType: String!, effectiveFrom: String!, effectiveTo: String!, current: Boolean!,
            filename: String!, contentType: String!, size: DocumentLong!, sha256: String!, title: String!, categories: [String!]!, labels: [String!]!
        }
        type DocumentVersionPage { items: [DocumentVersion!]!, offset: Int!, limit: Int!, hasMore: Boolean! }
        # Binary content encoded as standard Base64. Metadata queries never include content.
        type DocumentContent { filename: String!, contentType: String!, data: String! }
        type DocumentBucketPage { items: [DocumentBucket!]!, offset: Int!, limit: Int!, hasMore: Boolean! }
        type DocumentPage { items: [DocumentResource!]!, offset: Int!, limit: Int!, hasMore: Boolean! }
        input DocumentCreateBucketInput { name: String!, kind: DocumentBucketKind! }
        input DocumentMetadataInput { title: String!, categories: [String!] = [], labels: [String!] = [] }
        input DocumentUploadInput { filename: String!, contentType: String!, data: String!, metadata: DocumentMetadataInput! }
        input DocumentReviseInput { expectedVersionId: ID!, upload: DocumentUploadInput! }
        input DocumentFilterInput { category: String, label: String, search: String }
        extend type Query {
            documentBucket(enterprise: String!, bucketId: ID!): DocumentBucket!
            documentBuckets(enterprise: String!, parentId: ID, offset: Int! = 0, limit: Int! = 50): DocumentBucketPage!
            documentBucketMembers(enterprise: String!, bucketId: ID!): [DocumentMember!]!
            document(enterprise: String!, resourceId: ID!): DocumentResource!
            documents(enterprise: String!, bucketId: ID!, filter: DocumentFilterInput, offset: Int! = 0, limit: Int! = 50): DocumentPage!
            documentContent(enterprise: String!, resourceId: ID!): DocumentContent!
            documentVersions(enterprise: String!, resourceId: ID!, offset: Int! = 0, limit: Int! = 50): DocumentVersionPage!
            documentVersion(enterprise: String!, resourceId: ID!, versionId: ID!): DocumentVersion!
            documentVersionContent(enterprise: String!, resourceId: ID!, versionId: ID!): DocumentContent!
        }
        extend type Mutation {
            documentCreateBucket(enterprise: String!, input: DocumentCreateBucketInput!): DocumentBucket!
            documentGrantMember(enterprise: String!, bucketId: ID!, partyId: ID!, access: DocumentAccess!): Boolean!
            documentRevokeMember(enterprise: String!, bucketId: ID!, partyId: ID!): Boolean!
            documentUpload(enterprise: String!, bucketId: ID!, input: DocumentUploadInput!): DocumentResource!
            documentRevise(enterprise: String!, resourceId: ID!, input: DocumentReviseInput!): DocumentResource!
            documentRestoreVersion(enterprise: String!, resourceId: ID!, versionId: ID!, expectedVersionId: ID!): DocumentResource!
            documentUpdateMetadata(enterprise: String!, resourceId: ID!, input: DocumentMetadataInput!): DocumentResource!
            documentAddToBucket(enterprise: String!, bucketId: ID!, resourceId: ID!): Boolean!
            documentRemoveFromBucket(enterprise: String!, bucketId: ID!, resourceId: ID!): Boolean!
            documentArchive(enterprise: String!, resourceId: ID!): Boolean!
            documentRate(enterprise: String!, resourceId: ID!, score: Int!): DocumentResource!
            documentClearRating(enterprise: String!, resourceId: ID!): DocumentResource!
            documentAddChild(enterprise: String!, parentId: ID!, childId: ID!): Boolean!
            documentRemoveChild(enterprise: String!, parentId: ID!, childId: ID!): Boolean!
            documentAttachBucket(enterprise: String!, arrangementId: ID!, bucketId: ID!): Boolean!
            documentDetachBucket(enterprise: String!, arrangementId: ID!, bucketId: ID!): Boolean!
        }
        """;

    @Override public TypeDefinitionRegistry getTypeDefinitions() { return new SchemaParser().parse(SDL); }
    @Override public RuntimeWiring.Builder configureWiring(RuntimeWiring.Builder builder) {
        return builder.scalar(DocumentLongScalar.create())
                .type("Query", q -> q
                        .dataFetcher("documentBucket", fetch(e -> api().findBucket(enterprise(e), id(e, "bucketId"))))
                        .dataFetcher("documentBuckets", fetch(e -> api().listBuckets(enterprise(e), id(e, "parentId"), e.getArgument("offset"), e.getArgument("limit"))))
                        .dataFetcher("documentBucketMembers", fetch(e -> api().members(enterprise(e), id(e, "bucketId"))))
                        .dataFetcher("document", fetch(e -> api().find(enterprise(e), id(e, "resourceId"))))
                        .dataFetcher("documents", fetch(e -> api().list(enterprise(e), id(e, "bucketId"), filter(e), e.getArgument("offset"), e.getArgument("limit"))))
                        .dataFetcher("documentContent", fetch(e -> api().download(enterprise(e), id(e, "resourceId"))))
                        .dataFetcher("documentVersions", fetch(e -> api().versions(enterprise(e), id(e, "resourceId"), e.getArgument("offset"), e.getArgument("limit"))))
                        .dataFetcher("documentVersion", fetch(e -> api().version(enterprise(e), id(e, "resourceId"), id(e, "versionId"))))
                        .dataFetcher("documentVersionContent", fetch(e -> api().downloadVersion(enterprise(e), id(e, "resourceId"), id(e, "versionId")))))
                .type("DocumentContent", c -> c.dataFetcher("data", e -> Base64.getEncoder().encodeToString(((Content) e.getSource()).data())))
                .type("Mutation", m -> m
                        .dataFetcher("documentCreateBucket", fetch(e -> {
                            Map<String, Object> input = e.getArgument("input");
                            return api().createBucket(enterprise(e), new CreateBucket((String) input.get("name"), BucketKind.valueOf((String) input.get("kind"))));
                        }))
                        .dataFetcher("documentGrantMember", fetch(e -> api().grant(enterprise(e), id(e, "bucketId"), id(e, "partyId"), new Grant(Access.valueOf(e.getArgument("access")))).replaceWith(true)))
                        .dataFetcher("documentRevokeMember", fetch(e -> api().revoke(enterprise(e), id(e, "bucketId"), id(e, "partyId")).replaceWith(true)))
                        .dataFetcher("documentUpload", fetch(e -> api().upload(enterprise(e), id(e, "bucketId"), upload(e))))
                        .dataFetcher("documentRevise", fetch(e -> api().revise(enterprise(e), id(e, "resourceId"), revise(e))))
                        .dataFetcher("documentRestoreVersion", fetch(e -> api().restoreVersion(enterprise(e), id(e, "resourceId"), id(e, "versionId"), new VersionExpectation(id(e, "expectedVersionId")))))
                        .dataFetcher("documentUpdateMetadata", fetch(e -> api().updateMetadata(enterprise(e), id(e, "resourceId"), metadata(e.getArgument("input")))))
                        .dataFetcher("documentAddToBucket", fetch(e -> api().addToBucket(enterprise(e), id(e, "bucketId"), id(e, "resourceId")).replaceWith(true)))
                        .dataFetcher("documentRemoveFromBucket", fetch(e -> api().removeFromBucket(enterprise(e), id(e, "bucketId"), id(e, "resourceId")).replaceWith(true)))
                        .dataFetcher("documentArchive", fetch(e -> api().archive(enterprise(e), id(e, "resourceId")).replaceWith(true)))
                        .dataFetcher("documentRate", fetch(e -> api().rate(enterprise(e), id(e, "resourceId"), new Rate(e.getArgument("score")))))
                        .dataFetcher("documentClearRating", fetch(e -> api().clearRating(enterprise(e), id(e, "resourceId"))))
                        .dataFetcher("documentAddChild", fetch(e -> api().childBucket(enterprise(e), id(e, "parentId"), id(e, "childId"), false).replaceWith(true)))
                        .dataFetcher("documentRemoveChild", fetch(e -> api().childBucket(enterprise(e), id(e, "parentId"), id(e, "childId"), true).replaceWith(true)))
                        .dataFetcher("documentAttachBucket", fetch(e -> api().attachBucket(enterprise(e), id(e, "arrangementId"), id(e, "bucketId"), false).replaceWith(true)))
                        .dataFetcher("documentDetachBucket", fetch(e -> api().attachBucket(enterprise(e), id(e, "arrangementId"), id(e, "bucketId"), true).replaceWith(true))));
    }

    private static String enterprise(DataFetchingEnvironment e) { return e.getArgument("enterprise"); }
    private static UUID id(DataFetchingEnvironment e, String name) {
        String value = e.getArgument(name);
        return value == null ? null : UUID.fromString(value);
    }
    private static Filter filter(DataFetchingEnvironment e) {
        Map<String, Object> input = e.getArgument("filter");
        return input == null ? null : new Filter((String) input.get("category"), (String) input.get("label"), (String) input.get("search"));
    }
    @SuppressWarnings("unchecked")
    private static Metadata metadata(Map<String, Object> input) {
        return new Metadata((String) input.get("title"), (List<String>) input.get("categories"), (List<String>) input.get("labels"));
    }
    @SuppressWarnings("unchecked")
    private static Upload upload(DataFetchingEnvironment e) {
        return uploadInput(e.getArgument("input"));
    }
    @SuppressWarnings("unchecked")
    private static Upload uploadInput(Map<String, Object> input) {
        String encoded = (String) input.get("data");
        // Bound allocation before decoding; the service also checks the decoded length.
        if (encoded.length() > 4 * ((DocumentService.MAX_BYTES + 2) / 3))
            throw new BadRequestException("Document payload must be at most 16 MiB");
        return new Upload((String) input.get("filename"), (String) input.get("contentType"), Base64.getDecoder().decode(encoded),
                metadata((Map<String, Object>) input.get("metadata")));
    }
    @SuppressWarnings("unchecked")
    private static Revise revise(DataFetchingEnvironment e) {
        Map<String, Object> input = e.getArgument("input");
        return new Revise(UUID.fromString((String) input.get("expectedVersionId")), uploadInput((Map<String, Object>) input.get("upload")));
    }

    private static <T> DataFetcher<Object> fetch(Function<DataFetchingEnvironment, Uni<T>> operation) {
        return e -> {
            CallScoper scoper = null;
            boolean entered = false;
            try {
                // Vert.x supplies this server-owned context; GraphQL variables never supply identity.
                RoutingContext context = e.getGraphQlContext().get(RoutingContext.class);
                if (context != null) {
                    scoper = IGuiceContext.get(CallScoper.class);
                    scoper.enter();
                    entered = true;
                    CallScopeProperties properties = IGuiceContext.get(CallScopeProperties.class);
                    if (properties.getSource() == CallScopeSource.Unknown) properties.setSource(CallScopeSource.Http);
                    properties.getProperties().put("RoutingContext", context);
                    properties.getProperties().put("HttpServerRequest", context.request());
                    properties.getProperties().put("HttpServerResponse", context.response());
                }
                // Subscription captures the host identity before the API opens its stateless transaction.
                return operation.apply(e).subscribeAsCompletionStage().handle((result, failure) -> failure == null
                        ? DataFetcherResult.<T>newResult().data(result).build() : error(e, failure));
            } catch (Exception failure) {
                return error(e, failure);
            } finally {
                if (entered) scoper.exit();
            }
        };
    }
    private static DataFetcherResult<Object> error(DataFetchingEnvironment e, Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        String code, message;
        if (failure instanceof SecurityException) { code = "FORBIDDEN"; message = "Document access denied"; }
        else if (failure instanceof ClientErrorException client && client.getResponse().getStatus() == 409) {
            code = "CONFLICT"; message = "Document version changed; reload the current document";
        }
        else if (failure instanceof NotFoundException) { code = "NOT_FOUND"; message = "Document target unavailable"; }
        else if (failure instanceof IllegalArgumentException || failure instanceof BadRequestException) {
            code = "BAD_USER_INPUT"; message = "Invalid document input";
        } else {
            LOG.log(Level.SEVERE, "Document GraphQL operation failed", failure);
            code = "INTERNAL_SERVER_ERROR"; message = "Document operation failed";
        }
        return DataFetcherResult.newResult().error(GraphqlErrorBuilder.newError(e).message(message).extensions(Map.of("code", code)).build()).build();
    }
}
