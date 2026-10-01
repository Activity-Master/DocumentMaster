package com.guicedee.activitymaster.documents;

import com.guicedee.client.services.config.IGuiceScanModuleInclusions;
import java.util.Set;

public final class DocumentInclusionModule implements IGuiceScanModuleInclusions<DocumentInclusionModule> {
    @Override public Set<String> includeModules() { return Set.of("com.guicedee.activitymaster.documents"); }
}
