-- Test-only scale fixture. Clone canonical rows created through the real services.
-- Substitutions are UUIDs from the isolated Testcontainers fixture, never host input.
CREATE TEMP TABLE perf_docs AS SELECT n, md5('performance-document-' || n)::uuid id
FROM generate_series(1,12000) n;
CREATE TEMP TABLE perf_buckets AS SELECT n, md5('performance-bucket-' || n)::uuid id
FROM generate_series(1,1200) n;

INSERT INTO arrangement.arrangement
SELECT (jsonb_populate_record(NULL::arrangement.arrangement, to_jsonb(t) ||
    jsonb_build_object('arrangementid', b.id))).*
FROM arrangement.arrangement t CROSS JOIN perf_buckets b WHERE t.arrangementid='@BUCKET@';
INSERT INTO arrangement.arrangementxarrangementtype
SELECT (jsonb_populate_record(NULL::arrangement.arrangementxarrangementtype, to_jsonb(t) ||
    jsonb_build_object('arrangementxarrangementtypeid', gen_random_uuid(), 'arrangementid', b.id))).*
FROM arrangement.arrangementxarrangementtype t CROSS JOIN perf_buckets b WHERE t.arrangementid='@BUCKET@';
INSERT INTO arrangement.arrangementxclassification
SELECT (jsonb_populate_record(NULL::arrangement.arrangementxclassification, to_jsonb(t) ||
    jsonb_build_object('arrangementxclassificationid', gen_random_uuid(), 'arrangementid', b.id))).*
FROM arrangement.arrangementxclassification t CROSS JOIN perf_buckets b WHERE t.arrangementid='@BUCKET@';
INSERT INTO arrangement.arrangementxinvolvedparty
SELECT (jsonb_populate_record(NULL::arrangement.arrangementxinvolvedparty, to_jsonb(t) ||
    jsonb_build_object('arrangementxinvolvedpartyid', gen_random_uuid(), 'arrangementid', b.id,
        'effectivefromdate', timestamptz '2025-01-01' + v * interval '1 hour',
        'effectivetodate', CASE WHEN v=79 THEN timestamptz '2999-12-31' ELSE timestamptz '2025-01-01' + (v+1)*interval '1 hour' END))).*
FROM arrangement.arrangementxinvolvedparty t CROSS JOIN perf_buckets b CROSS JOIN generate_series(0,79) v
WHERE t.arrangementid='@BUCKET@';

INSERT INTO resource.resourceitem
SELECT (jsonb_populate_record(NULL::resource.resourceitem, to_jsonb(t) ||
    jsonb_build_object('resourceitemid', d.id, 'effectivefromdate', timestamptz '2025-01-01',
        'warehousecreatedtimestamp', timestamptz '2025-01-01' + d.n * interval '1 second'))).*
FROM resource.resourceitem t CROSS JOIN perf_docs d WHERE t.resourceitemid='@DOCUMENT@';
INSERT INTO resource.resourceitemxresourceitemtype
SELECT (jsonb_populate_record(NULL::resource.resourceitemxresourceitemtype, to_jsonb(t) ||
    jsonb_build_object('resourceitemxresourceitemtypeid', gen_random_uuid(), 'resourceitemid', d.id))).*
FROM resource.resourceitemxresourceitemtype t CROSS JOIN perf_docs d WHERE t.resourceitemid='@DOCUMENT@';
INSERT INTO resource.resourceitemdatavalue
SELECT d.id, t.resourceitemdatavalue FROM resource.resourceitemdatavalue t CROSS JOIN perf_docs d
WHERE t.resourceitemdatavalueid='@DOCUMENT@';
INSERT INTO party.involvedpartyxresourceitem
SELECT (jsonb_populate_record(NULL::party.involvedpartyxresourceitem, to_jsonb(t) ||
    jsonb_build_object('involvedpartyxresourceitemid', gen_random_uuid(), 'resourceitemid', d.id))).*
FROM party.involvedpartyxresourceitem t CROSS JOIN perf_docs d WHERE t.resourceitemid='@DOCUMENT@';
INSERT INTO arrangement.arrangementxresourceitem
SELECT (jsonb_populate_record(NULL::arrangement.arrangementxresourceitem, to_jsonb(t) ||
    jsonb_build_object('arrangementxresourceitemid', gen_random_uuid(), 'resourceitemid', d.id,
        'arrangementid', CASE WHEN d.n<=3000 THEN '@BUCKET@'::uuid WHEN d.n<=6000 THEN '@OTHER_BUCKET@'::uuid
            ELSE md5('performance-bucket-' || (1+d.n%1200))::uuid END))).*
FROM arrangement.arrangementxresourceitem t CROSS JOIN perf_docs d WHERE t.resourceitemid='@DOCUMENT@';

-- 8 contiguous versions per document; ratings stay current and resource-level.
INSERT INTO resource.resourceitemxclassification
SELECT (jsonb_populate_record(NULL::resource.resourceitemxclassification, to_jsonb(t) ||
    jsonb_build_object('resourceitemxclassificationid', gen_random_uuid(), 'resourceitemid', d.id,
        'effectivefromdate', timestamptz '2025-01-01' + v * interval '1 hour',
        'effectivetodate', CASE WHEN v=7 THEN timestamptz '2999-12-31' ELSE timestamptz '2025-01-01' + (v+1)*interval '1 hour' END,
        'value', CASE c.classificationname WHEN 'DocumentVersion' THEN d.id::text
            WHEN 'DocumentTitle' THEN CASE WHEN d.n%1000=0 THEN 'Needle title' ELSE 'Document '||d.n END
            WHEN 'DocumentCategory' THEN 'Category-'||(d.n%20) WHEN 'DocumentLabel' THEN 'Label-'||(d.n%10)
            ELSE t.value END))).*
FROM resource.resourceitemxclassification t JOIN classification.classification c USING(classificationid)
CROSS JOIN perf_docs d CROSS JOIN generate_series(0,7) v
WHERE t.resourceitemid='@DOCUMENT@' AND c.classificationname NOT LIKE 'DocumentRating%';
INSERT INTO resource.resourceitemxclassification
SELECT (jsonb_populate_record(NULL::resource.resourceitemxclassification, to_jsonb(t) ||
    jsonb_build_object('resourceitemxclassificationid', gen_random_uuid(), 'resourceitemid', d.id))).*
FROM resource.resourceitemxclassification t JOIN classification.classification c USING(classificationid)
CROSS JOIN perf_docs d WHERE t.resourceitemid='@DOCUMENT@' AND c.classificationname LIKE 'DocumentRating%';
ANALYZE;
