package com.guicedee.activitymaster.documents.test;

import com.google.inject.AbstractModule;
import com.guicedee.activitymaster.documents.DocumentIdentity;
import com.guicedee.activitymaster.documents.DocumentIdentityProvider;
import com.guicedee.client.IGuiceContext;
import com.guicedee.client.scopes.CallScopeProperties;
import com.guicedee.client.services.lifecycle.IGuiceModule;
import com.guicedee.vertx.web.spi.VertxHttpServerConfigurator;
import com.guicedee.vertx.web.spi.VertxRouterConfigurator;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Synthetic host credentials for loopback HTTP tests only; never production authentication. */
public final class DocumentTestHost extends AbstractModule implements IGuiceModule<DocumentTestHost> {
    private static final Map<String, DocumentIdentity> CREDENTIALS = new ConcurrentHashMap<>();
    static volatile HttpServer server;
    static String issue(DocumentIdentity identity) {
        String credential = UUID.randomUUID().toString();
        CREDENTIALS.put(credential, identity);
        return credential;
    }
    @Override protected void configure() { bind(DocumentIdentityProvider.class).to(IdentityProvider.class); }

    public static final class IdentityProvider implements DocumentIdentityProvider {
        @Override public Uni<DocumentIdentity> current() {
            RoutingContext context = (RoutingContext) IGuiceContext.get(CallScopeProperties.class).getProperties().get("RoutingContext");
            DocumentIdentity identity = context == null ? null : context.get("verifiedDocumentIdentity");
            if (context == null || identity == null)
                return new DocumentIdentityProvider.Deny().current();
            return Uni.createFrom().item(identity);
        }
    }
    public static final class Authentication implements VertxRouterConfigurator<Authentication> {
        // Install before the GraphQL terminal handler as well as REST endpoints.
        @Override public Integer sortOrder() { return Integer.MIN_VALUE + 75; }
        @Override public Router builder(Router router) {
            router.route().handler(context -> {
                String credential = context.request().getHeader("X-Document-Test-Credential");
                DocumentIdentity identity = credential == null ? null : CREDENTIALS.get(credential);
                if (identity != null) {
                    context.put("verifiedDocumentIdentity", identity);
                }
                context.next();
            });
            return router;
        }
    }
    public static final class ServerCapture implements VertxHttpServerConfigurator {
        @Override public HttpServer builder(HttpServer builder) { server = builder; return builder; }
    }
}
