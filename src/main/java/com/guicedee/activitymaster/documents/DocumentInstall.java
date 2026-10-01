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
        return getISystem(session, DocumentSystem.NAME, enterprise)
                .chain(system -> getISystemToken(session, DocumentSystem.NAME, enterprise).chain(token -> {
                    Uni<?> work = IGuiceContext.get(IArrangementsService.class)
                            .createArrangementType(session, DocumentTaxonomy.BUCKET_TYPE, system, token)
                            .chain(() -> IGuiceContext.get(IResourceItemService.class)
                                    .createType(session, DocumentTaxonomy.RESOURCE_TYPE, "Private document binary", system, token));
                    IClassificationService<?> classes = IGuiceContext.get(IClassificationService.class);
                    for (DocumentTaxonomy role : DocumentTaxonomy.values())
                        work = work.chain(() -> classes.create(session, role.classificationName(),
                                role.classificationName(), role.concept, system, token));
                    return work.replaceWith(true);
                }));
    }
}
