package com.guicedee.activitymaster.documents;

import com.google.inject.ImplementedBy;
import io.smallrye.mutiny.Uni;

/** The consuming host binds this to its authenticated, call-scoped identity. */
@ImplementedBy(DocumentIdentityProvider.Deny.class)
public interface DocumentIdentityProvider {
    Uni<DocumentIdentity> current();

    final class Deny implements DocumentIdentityProvider {
        @Override public Uni<DocumentIdentity> current() {
            return Uni.createFrom().failure(new SecurityException("Authenticated document identity required"));
        }
    }
}
