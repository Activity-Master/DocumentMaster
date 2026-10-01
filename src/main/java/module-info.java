module com.guicedee.activitymaster.documents {
    requires transitive com.guicedee.activitymaster.fsdm;
    requires com.guicedee.rest;
    requires com.guicedee.vertx.graphql;
    requires java.logging;
    exports com.guicedee.activitymaster.documents;
    exports com.guicedee.activitymaster.documents.rest;
    exports com.guicedee.activitymaster.documents.graphql;
    opens com.guicedee.activitymaster.documents to com.google.guice, tools.jackson.databind;
    opens com.guicedee.activitymaster.documents.rest to com.google.guice, com.guicedee.rest, tools.jackson.databind;
    opens com.guicedee.activitymaster.documents.graphql to com.google.guice;
    provides com.guicedee.vertx.graphql.services.IGraphQLSchemaProvider
        with com.guicedee.activitymaster.documents.graphql.DocumentGraphQLSchemaProvider;
    provides com.guicedee.client.services.lifecycle.IGuiceModule with com.guicedee.activitymaster.documents.DocumentModule;
    provides com.guicedee.client.services.config.IGuiceScanModuleInclusions with com.guicedee.activitymaster.documents.DocumentInclusionModule;
    provides com.guicedee.activitymaster.fsdm.client.services.systems.IMasterSystem with com.guicedee.activitymaster.documents.DocumentSystem;
    provides com.guicedee.activitymaster.fsdm.client.services.systems.ISystemUpdate with com.guicedee.activitymaster.documents.DocumentInstall,
        com.guicedee.activitymaster.documents.DocumentVersionInstall;
}
