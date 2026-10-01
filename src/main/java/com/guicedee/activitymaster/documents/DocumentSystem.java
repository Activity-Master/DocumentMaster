package com.guicedee.activitymaster.documents;

import com.google.inject.Inject;
import com.guicedee.activitymaster.fsdm.client.services.ISystemsService;
import com.guicedee.activitymaster.fsdm.client.services.administration.MasterDefaultSystem;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.client.services.systems.IMasterSystem;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

public final class DocumentSystem extends MasterDefaultSystem<DocumentSystem>
        implements IMasterSystem<DocumentSystem> {
    public static final String NAME = "Document Master";
    @Inject private ISystemsService<?> systems;

    @Override public Uni<ISystems<?, ?>> registerSystem(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise) {
        return systems.create(session, enterprise, getSystemName(), getSystemDescription())
                .chain(system -> systems.registerNewSystem(session, enterprise, system).replaceWith(system));
    }
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
