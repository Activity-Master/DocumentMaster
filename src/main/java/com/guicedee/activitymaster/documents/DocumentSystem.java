package com.guicedee.activitymaster.documents;

import com.guicedee.activitymaster.fsdm.client.services.administration.MasterDefaultPlugin;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

public final class DocumentSystem extends MasterDefaultPlugin<DocumentSystem> {
    public static final String NAME = "Document Master";

    @Override public Uni<Void> createDefaults(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise) {
        return Uni.createFrom().voidItem();
    }
    @Override public Uni<Void> postStartup(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise) {
        return Uni.createFrom().voidItem();
    }
    @Override public String getSystemName() { return NAME; }
    @Override public String getSystemDescription() { return "FSDM document resources, buckets, metadata and ratings"; }
    @Override public Integer sortOrder() { return 1185; }
    @Override public int totalTasks() { return 1; }
}
