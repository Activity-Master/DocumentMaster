package com.guicedee.activitymaster.documents;

import com.guicedee.activitymaster.fsdm.client.services.classifications.EnterpriseClassificationDataConcepts;

/** Every property is installed against the FSDM relationship it belongs to. */
enum DocumentTaxonomy {
    BucketType(EnterpriseClassificationDataConcepts.ArrangementXArrangementType),
    BucketContext(EnterpriseClassificationDataConcepts.ArrangementXClassification),
    BucketName(EnterpriseClassificationDataConcepts.ArrangementXClassification),
    BucketKind(EnterpriseClassificationDataConcepts.ArrangementXClassification),
    BucketMember(EnterpriseClassificationDataConcepts.ArrangementXInvolvedParty),
    BucketChild(EnterpriseClassificationDataConcepts.ArrangementXArrangement),
    BucketAttachment(EnterpriseClassificationDataConcepts.ArrangementXArrangement),
    BucketDocument(EnterpriseClassificationDataConcepts.ArrangementXResourceItem),
    ResourceType(EnterpriseClassificationDataConcepts.ResourceItemXResourceItemType),
    Version(EnterpriseClassificationDataConcepts.ResourceItemXClassification),
    Owner(EnterpriseClassificationDataConcepts.InvolvedPartyXResourceItem),
    Rating(EnterpriseClassificationDataConcepts.InvolvedPartyXResourceItem),
    Filename(EnterpriseClassificationDataConcepts.ResourceItemXClassification),
    ContentType(EnterpriseClassificationDataConcepts.ResourceItemXClassification),
    Size(EnterpriseClassificationDataConcepts.ResourceItemXClassification),
    Sha256(EnterpriseClassificationDataConcepts.ResourceItemXClassification),
    Title(EnterpriseClassificationDataConcepts.ResourceItemXClassification),
    Category(EnterpriseClassificationDataConcepts.ResourceItemXClassification),
    Label(EnterpriseClassificationDataConcepts.ResourceItemXClassification),
    RatingSum(EnterpriseClassificationDataConcepts.ResourceItemXClassification),
    RatingCount(EnterpriseClassificationDataConcepts.ResourceItemXClassification),
    RatingAverage(EnterpriseClassificationDataConcepts.ResourceItemXClassification);

    static final String RESOURCE_TYPE = "Document";
    static final String BUCKET_TYPE = "Document Bucket";
    final EnterpriseClassificationDataConcepts concept;
    DocumentTaxonomy(EnterpriseClassificationDataConcepts concept) { this.concept = concept; }
    String classificationName() { return "Document" + name(); }
}
