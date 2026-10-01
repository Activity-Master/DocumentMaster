package com.guicedee.activitymaster.documents;

import com.google.inject.PrivateModule;
import com.google.inject.Singleton;
import com.guicedee.client.services.lifecycle.IGuiceModule;

public final class DocumentModule extends PrivateModule implements IGuiceModule<DocumentModule> {
    @Override protected void configure() {
        bind(IDocumentService.class).to(DocumentService.class).in(Singleton.class);
        expose(IDocumentService.class);
        bind(DocumentApi.class).in(Singleton.class);
        expose(DocumentApi.class);
    }
}
