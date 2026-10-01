package com.guicedee.activitymaster.documents;

import com.guicedee.activitymaster.fsdm.client.services.IClassificationService;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise;
import com.guicedee.activitymaster.fsdm.client.services.systems.ISystemUpdate;
import com.guicedee.activitymaster.fsdm.client.services.systems.SortedUpdate;
import com.guicedee.client.IGuiceContext;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import static com.guicedee.activitymaster.fsdm.client.services.IActivityMasterService.*;

/** Separate update for enterprises whose original Document Master taxonomy was already installed. */
@SortedUpdate(sortOrder = 1186, taskCount = 1)
public final class DocumentVersionInstall implements ISystemUpdate {
    @Override public Uni<Boolean> update(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise) {
        return getISystem(session, DocumentSystem.NAME, enterprise)
                .chain(system -> getISystemToken(session, DocumentSystem.NAME, enterprise).chain(token ->
                        IGuiceContext.get(IClassificationService.class).create(session, DocumentTaxonomy.Version.classificationName(),
                                DocumentTaxonomy.Version.classificationName(), DocumentTaxonomy.Version.concept, system, token)))
                .replaceWith(true);
    }
}
