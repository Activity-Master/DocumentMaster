open module documents.master.tests {
    requires com.guicedee.activitymaster.documents;
    requires com.guicedee.guicedinjection;
    requires org.junit.jupiter.api;
    requires org.testcontainers;
    requires java.net.http;
    requires com.guicedee.rest;
    exports com.guicedee.activitymaster.documents.test;
    provides com.guicedee.client.services.lifecycle.IGuiceModule
        with com.guicedee.activitymaster.documents.test.PostgreSQLTestDBModule,
             com.guicedee.activitymaster.documents.test.DocumentTestHost;
    provides com.guicedee.vertx.web.spi.VertxRouterConfigurator
        with com.guicedee.activitymaster.documents.test.DocumentTestHost.Authentication;
    provides com.guicedee.vertx.web.spi.VertxHttpServerConfigurator
        with com.guicedee.activitymaster.documents.test.DocumentTestHost.ServerCapture;
}
