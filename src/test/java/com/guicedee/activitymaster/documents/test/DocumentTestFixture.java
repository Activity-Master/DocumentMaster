package com.guicedee.activitymaster.documents.test;

import com.google.inject.Key;
import com.google.inject.name.Names;
import com.guicedee.activitymaster.documents.*;
import com.guicedee.activitymaster.fsdm.client.services.*;
import com.guicedee.activitymaster.fsdm.client.services.administration.ActivityMasterConfiguration;
import com.guicedee.client.IGuiceContext;
import com.guicedee.client.utils.Pair;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple4;
import org.hibernate.reactive.mutiny.Mutiny;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;

/** One canonical FSDM database and isolated HTTP listener shared by this test suite. */
final class DocumentTestFixture {
    static final String ENTERPRISE = "DocumentTest";
    IDocumentService service;
    UUID enterpriseId, actorId, recipientId, outsiderId, token;
    private final java.util.Map<UUID, UUID> tokens = new java.util.HashMap<>();
    UUID tokenFor(UUID party) { return tokens.get(party); }
    private static DocumentTestFixture instance;
    static synchronized DocumentTestFixture get() {
        if (instance == null) instance = new DocumentTestFixture();
        return instance;
    }
    private DocumentTestFixture() {
        // Vert.x context-local keys must be registered before creating any contexts.
        new com.guicedee.client.scopes.CallScoper();
        System.setProperty("HTTP_PORT", "0");
        System.setProperty("HTTP_HOST", "127.0.0.1");
        System.setProperty("HTTPS_ENABLED", "false");
        System.setProperty("HTTP_HANDLE_FILE_UPLOADS", "false");
        var database = PostgreSQLTestDBModule.DATABASE;
        System.setProperty("ENVIRONMENT", "test");
        System.setProperty("FSDM_DBSERVER", "127.0.0.1");
        System.setProperty("FSDM_DBPORT", database.getFirstMappedPort().toString());
        System.setProperty("FSDM_DBNAME", database.getDatabaseName());
        System.setProperty("FSDM_USER", database.getUsername());
        System.setProperty("FSDM_PASSWORD", database.getPassword());
        System.setProperty("FSDM_SSL_MODE", "disable");
        ActivityMasterConfiguration.get().setApplicationEnterpriseName(ENTERPRISE);
        IGuiceContext.instance();
        Mutiny.SessionFactory factory = IGuiceContext.get(Key.get(Mutiny.SessionFactory.class, Names.named("ActivityMaster-Test")));
        service = IGuiceContext.get(IDocumentService.class);
        IEnterpriseService<?> enterprises = IGuiceContext.get(IEnterpriseService.class);
        factory.withStatelessSession(session -> enterprises.startNewEnterprise(session, ENTERPRISE, "admin", "adminadmin!@"))
                .await().atMost(Duration.ofMinutes(5));
        factory.withStatelessTransaction(session -> enterprises.getEnterprise(session, ENTERPRISE)
                .chain(e -> enterprises.loadUpdates(session, e))).await().atMost(Duration.ofMinutes(5));
        run(c -> {
            enterpriseId = c.getItem2().getId();
            return PluginTestFixture.user(c.getItem1(), c.getItem2()).chain(actor -> {
                actorId = actor.partyId(); token = actor.identityToken(); tokens.put(actor.partyId(), actor.identityToken());
                return PluginTestFixture.enable(c.getItem1(), c.getItem3(), actor);
            }).chain(() -> PluginTestFixture.user(c.getItem1(), c.getItem2())).chain(recipient -> {
                recipientId = recipient.partyId(); tokens.put(recipient.partyId(), recipient.identityToken());
                return PluginTestFixture.enable(c.getItem1(), c.getItem3(), recipient);
            }).chain(() -> PluginTestFixture.user(c.getItem1(), c.getItem2())).chain(outsider -> {
                outsiderId = outsider.partyId(); tokens.put(outsider.partyId(), outsider.identityToken());
                return PluginTestFixture.enable(c.getItem1(), c.getItem3(), outsider);
            });
        });
    }

    <T> T run(Function<Tuple4<Mutiny.StatelessSession,
            com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise<?, ?>,
            com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems<?, ?>, UUID[]>, Uni<T>> work) {
        return SessionUtils.withActivityMaster(ENTERPRISE, DocumentSystem.NAME, work)
                .await().atMost(Duration.ofMinutes(2));
    }

}
