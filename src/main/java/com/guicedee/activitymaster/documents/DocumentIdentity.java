package com.guicedee.activitymaster.documents;

import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import java.util.Objects;
import java.util.UUID;

/** A host-resolved actor and FSDM context. This is never request body data. */
public record DocumentIdentity(UUID partyId, UUID enterpriseId, ActivityScope.Context context, UUID identityToken, UUID installationPartyId) {
    public DocumentIdentity(UUID partyId, UUID enterpriseId, ActivityScope.Context context, UUID identityToken) {
        this(partyId, enterpriseId, context, identityToken, partyId);
    }
    public DocumentIdentity {
        Objects.requireNonNull(installationPartyId, "installationPartyId");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(enterpriseId, "enterpriseId");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(identityToken, "identityToken");
        if (context.realm() == ActivityScope.Realm.WORK && !enterpriseId.equals(context.ownerId()))
            throw new SecurityException("Work documents require the authorized enterprise context");
        if (context.realm() != ActivityScope.Realm.WORK && !partyId.equals(context.ownerId()))
            throw new SecurityException("Personal and social documents require the verified party context");
    }
    public com.guicedee.activitymaster.fsdm.plugins.PluginModels.Identity user() {
        return new com.guicedee.activitymaster.fsdm.plugins.PluginModels.Identity(partyId, enterpriseId, identityToken);
    }
    public UUID[] tokens() { return new UUID[]{identityToken}; }
}
