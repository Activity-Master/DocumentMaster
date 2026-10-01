package com.guicedee.activitymaster.documents.test;

import com.guicedee.activitymaster.fsdm.db.FsdmSchema;

import com.guicedee.client.services.lifecycle.IGuiceModule;
import com.guicedee.persistence.*;
import com.guicedee.persistence.annotations.EntityManager;
import com.guicedee.persistence.implementations.postgres.PostgresConnectionBaseInfo;
import org.hibernate.jpa.boot.spi.PersistenceUnitDescriptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

@EntityManager(value = "ActivityMaster-Test", defaultEm = true)
public class PostgreSQLTestDBModule extends DatabaseModule<PostgreSQLTestDBModule>
        implements IGuiceModule<PostgreSQLTestDBModule> {
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("fsdm").withUsername("postgres").withPassword("postgres");
    static {
        DATABASE.start();
        try {
            FsdmSchema.forEachScript((script, sql) -> {
                DATABASE.copyFileToContainer(Transferable.of(sql.getBytes(StandardCharsets.UTF_8)),
                        "/tmp/" + script);
                var scriptResult = DATABASE.execInContainer("psql", "-v", "ON_ERROR_STOP=1",
                        "-U", DATABASE.getUsername(), "-d", DATABASE.getDatabaseName(), "-f", "/tmp/" + script);
                if (scriptResult.getExitCode() != 0) {
                    throw new IllegalStateException("psql failed on " + script + ": " + scriptResult.getStderr());
                }
            });
        } catch (Exception e) {
            DATABASE.stop();
            throw new ExceptionInInitializerError(e);
        }
    }
    @Override protected String getPersistenceUnitName() { return "ActivityMaster-Test"; }
    @Override protected String getJndiMapping() { return "jdbc:activitymaster-test"; }
    @Override public Integer sortOrder() { return 10; }
    @Override protected ConnectionBaseInfo getConnectionBaseInfo(PersistenceUnitDescriptor unit, Properties properties) {
        var info = new PostgresConnectionBaseInfo();
        info.setServerName(DATABASE.getHost());
        info.setPort(String.valueOf(DATABASE.getFirstMappedPort()));
        info.setDatabaseName(DATABASE.getDatabaseName());
        info.setUsername(DATABASE.getUsername());
        info.setPassword(DATABASE.getPassword());
        info.setDefaultConnection(true);
        info.setReactive(true);
        return info;
    }
}

