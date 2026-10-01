package com.guicedee.activitymaster.documents;

import com.guicedee.activitymaster.fsdm.client.services.*;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise;
import com.guicedee.activitymaster.fsdm.client.services.systems.ISystemUpdate;
import com.guicedee.activitymaster.fsdm.client.services.systems.SortedUpdate;
import com.guicedee.client.IGuiceContext;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import static com.guicedee.activitymaster.fsdm.client.services.IActivityMasterService.*;

/** Taxonomy only: uses the existing FSDM schema, with no parallel document tables. */
@SortedUpdate(sortOrder = 1185, taskCount = 1)
public final class DocumentInstall implements ISystemUpdate {
    @Override public Uni<Boolean> update(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise) {
        ISystemsService<?> systems = IGuiceContext.get(ISystemsService.class);
        var extension = IGuiceContext.get(DocumentSystem.class);
        return systems.getActivityMaster(session, enterprise)
                .chain(core -> systems.getSecurityIdentityToken(session, core)
                        .chain(bootstrapToken -> IGuiceContext.get(com.guicedee.activitymaster.fsdm.plugins.PluginService.class)
                                .registerBuiltIn(session, core, bootstrapToken, extension)
                                .chain(() -> extension.getSystem(session, enterprise))
                                .chain(system -> {
                    Uni<?> work = IGuiceContext.get(IArrangementsService.class)
                            .createArrangementType(session, DocumentTaxonomy.BUCKET_TYPE, system, bootstrapToken)
                            .chain(() -> IGuiceContext.get(IResourceItemService.class)
                                    .createType(session, DocumentTaxonomy.RESOURCE_TYPE, "Private document binary", system, bootstrapToken));
                    IClassificationService<?> classes = IGuiceContext.get(IClassificationService.class);
                    for (DocumentTaxonomy role : DocumentTaxonomy.values())
                        work = work.chain(() -> classes.create(session, role.classificationName(),
                                role.classificationName(), role.concept, system, bootstrapToken));
                    return work.replaceWith(true);
                })));
    }
}
