package com.guicedee.activitymaster.documents;

import com.guicedee.activitymaster.documents.DocumentModels.*;
import com.guicedee.activitymaster.fsdm.client.services.IActiveFlagService;
import com.guicedee.activitymaster.fsdm.client.services.ISecurityTokenService;
import com.guicedee.activitymaster.fsdm.client.services.IResourceItemService;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import com.google.inject.Inject;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ClientErrorException;
import org.hibernate.reactive.mutiny.Mutiny;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/** Stateless FSDM storage. Private resources are reached through current bucket membership. */
public final class DocumentService implements IDocumentService {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_MEMBERS = 100;
    private static final OffsetDateTime END = OffsetDateTime.parse("2999-12-31T23:59:59Z");
    @Inject private IActiveFlagService<?> flags;
    @Inject private ISecurityTokenService<?> security;
    @Inject private IResourceItemService<?> resources;

    private record Scope(ISystems<?, ?> system, DocumentIdentity identity, List<UUID> tokens,
                         UUID flag, Map<DocumentTaxonomy, UUID> roles) {
        UUID enterprise() { return identity.enterpriseId(); }
        UUID actor() { return identity.partyId(); }
        String context() { return identity.context().realm().name() + ":" + identity.context().ownerId(); }
        UUID role(DocumentTaxonomy role) {
            UUID id = roles.get(role);
            if (id == null) throw new IllegalStateException("Document taxonomy unavailable: " + role);
            return id;
        }
    }

    private static String live(String a) {
        return a + ".enterpriseid=:enterprise and " + a + ".effectivefromdate<=statement_timestamp()"
                + " and " + a + ".effectivetodate>statement_timestamp()"
                + " and " + allowed(a);
    }
    private static String allowed(String a) {
        // A scalar lookup keeps this PK check out of the join search for every SCD alias.
        // Missing flags still deny access; no flag state is cached between statements.
        return "coalesce((select f.allowaccess from dbo.activeflag f where f.activeflagid=" + a + ".activeflagid),0)=1";
    }
    private static String owned(String a) { return live(a) + " and " + a + ".systemid=:system"; }
    private static String role(String a, DocumentTaxonomy role) {
        return owned(a) + " and " + a + ".classificationname='" + role.classificationName() + "'";
    }
    private static void transaction(Mutiny.StatelessSession session) {
        if (session == null || session.currentTransaction() == null)
            throw new IllegalStateException("Document writes require a caller-owned stateless transaction");
    }
    private static void page(int offset, int limit) {
        if (offset < 0 || offset > 10_000 || limit < 1 || limit > 100)
            throw new BadRequestException("Offset must be 0..10000 and limit 1..100");
    }
    private static UUID id(UUID value) {
        if (value == null) throw new BadRequestException("Target ID required");
        return value;
    }
    private static String text(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new BadRequestException(name + " must be 1.." + max + " characters");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) throw new BadRequestException(name + " must be a single line");
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new BadRequestException(name + " contains invalid Unicode");
            } else if (Character.isLowSurrogate(c)) throw new BadRequestException(name + " contains invalid Unicode");
        }
        return value.strip();
    }
    private static Metadata metadata(Metadata request) {
        if (request == null) throw new BadRequestException("Metadata required");
        if (request.categories().size() > 32 || request.labels().size() > 32)
            throw new BadRequestException("At most 32 categories and 32 labels");
        return new Metadata(text(request.title(), "Title", 150),
                request.categories().stream().map(v -> text(v, "Category", 150)).distinct().sorted().toList(),
                request.labels().stream().map(v -> text(v, "Label", 150)).distinct().sorted().toList());
    }

    private Uni<Scope> actor(Mutiny.StatelessSession session, ISystems<?, ?> system,
                             DocumentIdentity identity, String permission) {
        if (system == null || identity == null || system.getEnterprise() == null
                || !DocumentSystem.NAME.equals(system.getName())
                || !identity.enterpriseId().equals(system.getEnterprise().getId()))
            throw new SecurityException("Document system scope mismatch");
        if (session == null) throw new BadRequestException("Session required");
        if (!permission.equals("readallowed")) transaction(session);
        return com.guicedee.client.IGuiceContext.get(com.guicedee.activitymaster.fsdm.plugins.PluginService.class)
                .checkBuiltIn(session, system, identity.user(), identity.installationPartyId())
                .chain(() -> session.createNativeQuery("select 1 from security.securitytoken k where k.securitytoken=:token and " + live("k"), Integer.class)
                .setParameter("token", identity.identityToken().toString()).setParameter("enterprise", identity.enterpriseId())
                .setMaxResults(1).getResultList()).chain(found -> {
                    if (found.isEmpty()) return Uni.createFrom().failure(new SecurityException("Caller token unavailable"));
                    return session.createNativeQuery("select 1 from party.involvedparty p where p.involvedpartyid=:actor and " + live("p")
                                    + " and (:work=true or exists (select 1 from party.involvedpartyorganic o where o.involvedpartyorganicid=p.involvedpartyid and " + live("o") + "))", Integer.class)
                            .setParameter("actor", identity.partyId()).setParameter("enterprise", identity.enterpriseId())
                            .setParameter("work", identity.context().realm() == ActivityScope.Realm.WORK).setMaxResults(1).getResultList();
                }).chain(found -> {
                    if (found.isEmpty()) return Uni.createFrom().failure(new SecurityException("Actor unavailable"));
                    return security.getApplicableSecurityTokenIds(session, system, identity.tokens());
                }).chain(tokens -> {
                    if (tokens == null || tokens.isEmpty()) return Uni.createFrom().failure(new SecurityException("Actor token denied"));
                    return session.createNativeQuery("select 1 from dbo.systemssecuritytoken g join dbo.systems sys on sys.systemid=g.systemid"
                                    + " where g.systemid=:system and g.securitytokenid in (:tokens)"
                                    + " and g.readallowed=1 and g." + permission + "=1 and " + owned("g") + " and " + live("sys"), Integer.class)
                            .setParameter("tokens", tokens).setParameter("system", system.getId()).setParameter("enterprise", identity.enterpriseId())
                            .setMaxResults(1).getResultList().chain(grants -> {
                                if (grants.isEmpty()) return Uni.createFrom().failure(new SecurityException("Document system permission denied"));
                                return session.createNativeQuery("select 1 from party.involvedpartysecuritytoken g where g.involvedpartyid=:actor"
                                                + " and g.securitytokenid in (:tokens) and g.readallowed=1 and " + live("g"), Integer.class)
                                        .setParameter("actor", identity.partyId()).setParameter("tokens", tokens)
                                        .setParameter("enterprise", identity.enterpriseId()).setMaxResults(1).getResultList();
                            }).chain(grants -> {
                                if (grants.isEmpty()) return Uni.createFrom().failure(new SecurityException("Actor token denied"));
                                if (permission.equals("readallowed")) {
                                    return session.createNativeQuery("select c.classificationid from classification.classification c"
                                                    + " join classification.classificationdataconcept d on d.classificationdataconceptid=c.classificationdataconceptid"
                                                    + " where c.classificationname='DocumentVersion' and d.classificationdataconceptname='ResourceItemXClassification'"
                                                    + " and " + owned("c") + " and " + live("d"), UUID.class)
                                            .setParameter("system", system.getId()).setParameter("enterprise", identity.enterpriseId()).getResultList()
                                            .map(roles -> {
                                                if (roles.size() != 1) throw new IllegalStateException("Document version taxonomy unavailable");
                                                return new Scope(system, identity, List.copyOf(tokens), null, Map.of(DocumentTaxonomy.Version, roles.getFirst()));
                                            });
                                }
                                return flags.getActiveFlag(session, system.getEnterprise(), identity.tokens()).chain(flag ->
                                        session.createNativeQuery("select c.classificationname,c.classificationid,d.classificationdataconceptname from classification.classification c"
                                                        + " join classification.classificationdataconcept d on d.classificationdataconceptid=c.classificationdataconceptid"
                                                        + " where " + owned("c") + " and " + live("d"), Object[].class)
                                                .setParameter("system", system.getId()).setParameter("enterprise", identity.enterpriseId())
                                                .getResultList().map(rows -> {
                                                    Map<DocumentTaxonomy, UUID> roles = new EnumMap<>(DocumentTaxonomy.class);
                                                    for (DocumentTaxonomy r : DocumentTaxonomy.values()) {
                                                        List<Object[]> matches = rows.stream().filter(v -> r.classificationName().equals(v[0]) && r.concept.name().equals(v[2])).toList();
                                                        if (matches.size() != 1) throw new IllegalStateException("Document taxonomy unavailable: " + r);
                                                        roles.put(r, (UUID) matches.getFirst()[1]);
                                                    }
                                                    return new Scope(system, identity, List.copyOf(tokens), flag.getId(), roles);
                                                }));
                            });
                });
    }

    private Uni<Void> insert(Mutiny.StatelessSession session, Scope scope, String table, String key, UUID id, Map<String, Object> values) {
        return insert(session, scope, table, key, id, values, OffsetDateTime.now(ZoneOffset.UTC));
    }
    private Uni<Void> insert(Mutiny.StatelessSession session, Scope scope, String table, String key, UUID id, Map<String, Object> values, OffsetDateTime now) {
        Map<String, Object> columns = new LinkedHashMap<>(values);
        columns.put(key, id); columns.put("enterpriseid", scope.enterprise()); columns.put("systemid", scope.system().getId());
        columns.put("originalsourcesystemid", scope.system().getId()); columns.put("originalsourcesystemuniqueid", new UUID(0, 0));
        columns.put("activeflagid", scope.flag()); columns.put("effectivefromdate", now); columns.put("effectivetodate", END);
        columns.put("warehousecreatedtimestamp", now); columns.put("warehouselastupdatedtimestamp", now); columns.put("warehousefromdate", now.toLocalDate());
        var query = session.createNativeQuery("insert into " + table + " (" + String.join(",", columns.keySet()) + ") values ("
                + columns.keySet().stream().map(c -> ":" + c).collect(Collectors.joining(",")) + ")");
        columns.forEach(query::setParameter);
        return query.executeUpdate().replaceWithVoid();
    }
    private Uni<Void> property(Mutiny.StatelessSession session, Scope scope, boolean resource, UUID target, DocumentTaxonomy role, String value) {
        return property(session, scope, resource, target, role, value, OffsetDateTime.now(ZoneOffset.UTC));
    }
    private Uni<Void> property(Mutiny.StatelessSession session, Scope scope, boolean resource, UUID target, DocumentTaxonomy role, String value, OffsetDateTime at) {
        String table = resource ? "resource.resourceitemxclassification" : "arrangement.arrangementxclassification";
        String key = resource ? "resourceitemxclassificationid" : "arrangementxclassificationid";
        return insert(session, scope, table, key, UUID.randomUUID(),
                Map.of(resource ? "resourceitemid" : "arrangementid", target, "classificationid", scope.role(role), "value", value), at);
    }
    private Uni<Void> expire(Mutiny.StatelessSession session, Scope scope, String table, String predicate, Map<String, Object> parameters) {
        var query = session.createNativeQuery("update " + table + " x set effectivetodate=statement_timestamp(), warehouselastupdatedtimestamp=statement_timestamp()"
                + " where " + owned("x") + " and " + predicate);
        query.setParameter("enterprise", scope.enterprise()).setParameter("system", scope.system().getId());
        parameters.forEach(query::setParameter);
        return query.executeUpdate().replaceWithVoid();
    }
    private Uni<Void> expireAt(Mutiny.StatelessSession session, Scope scope, String table, String predicate, Map<String, Object> parameters, OffsetDateTime at) {
        var query = session.createNativeQuery("update " + table + " x set effectivetodate=:at,warehouselastupdatedtimestamp=:at"
                + " where " + owned("x") + " and " + predicate);
        query.setParameter("enterprise", scope.enterprise()).setParameter("system", scope.system().getId()).setParameter("at", at);
        parameters.forEach(query::setParameter);
        return query.executeUpdate().replaceWithVoid();
    }

    private static String bucketWhere() {
        return " from arrangement.arrangement a"
                + " join arrangement.arrangementxarrangementtype at on at.arrangementid=a.arrangementid"
                + " join arrangement.arrangementtype t on t.arrangementtypeid=at.arrangementtypeid"
                + " join classification.classification tc on tc.classificationid=at.classificationid"
                + " join arrangement.arrangementxclassification cx on cx.arrangementid=a.arrangementid"
                + " join classification.classification cc on cc.classificationid=cx.classificationid"
                + " join arrangement.arrangementxinvolvedparty m on m.arrangementid=a.arrangementid"
                + " join classification.classification mc on mc.classificationid=m.classificationid"
                + " where " + owned("a") + " and " + owned("at") + " and " + owned("t")
                + " and " + role("tc", DocumentTaxonomy.BucketType) + " and t.arrangementtypename='Document Bucket'"
                + " and " + owned("cx") + " and " + role("cc", DocumentTaxonomy.BucketContext)
                + " and (cx.value=:context or (:social=true and cx.value like 'SOCIAL:%'))"
                + " and " + owned("m") + " and " + role("mc", DocumentTaxonomy.BucketMember)
                + " and m.involvedpartyid=:actor";
    }
    private <T> Mutiny.SelectionQuery<T> bind(Mutiny.SelectionQuery<T> query, Scope scope) {
        return query.setParameter("enterprise", scope.enterprise()).setParameter("system", scope.system().getId())
                .setParameter("context", scope.context()).setParameter("social", scope.identity().context().realm() == ActivityScope.Realm.SOCIAL)
                .setParameter("actor", scope.actor());
    }
    private Uni<Void> bucket(Mutiny.StatelessSession session, Scope scope, UUID bucket, String access, boolean lock) {
        id(bucket);
        String permission = switch (access) {
            case "OWNER" -> " and m.value='OWNER'";
            case "EDITOR" -> " and m.value in ('OWNER','EDITOR')";
            default -> " and m.value in ('OWNER','EDITOR','READER')";
        };
        return bind(session.createNativeQuery("select a.arrangementid" + bucketWhere() + " and a.arrangementid=:id" + permission
                        + (lock ? " for update of a" : ""), UUID.class), scope)
                .setParameter("id", bucket).setMaxResults(1).getResultList().chain(rows -> rows.isEmpty()
                        ? Uni.createFrom().failure(new NotFoundException()) : Uni.createFrom().voidItem());
    }
    private static String resourceWhere() {
        return " from resource.resourceitem r"
                + " where " + owned("r") + " and r.resourceitemdatatype='Document'"
                + " and exists (select 1 from resource.resourceitemxresourceitemtype rt"
                + " join resource.resourceitemtype t on t.resourceitemtypeid=rt.resourceitemtypeid"
                + " join classification.classification tr on tr.classificationid=rt.classificationid"
                + " where rt.resourceitemid=r.resourceitemid and " + owned("rt") + " and " + owned("t")
                + " and " + role("tr", DocumentTaxonomy.ResourceType) + " and t.resourceitemtypename='Document' offset 0)";
    }
    private static String documentWhere() {
        return resourceWhere()
                + " and exists (select 1" + bucketWhere() + " and m.value in ('OWNER','EDITOR','READER')"
                + " and exists (select 1 from arrangement.arrangementxresourceitem link"
                + " join classification.classification lc on lc.classificationid=link.classificationid"
                + " where link.arrangementid=a.arrangementid and link.resourceitemid=r.resourceitemid"
                + " and " + owned("link") + " and " + role("lc", DocumentTaxonomy.BucketDocument) + "))";
    }
    private Uni<Void> document(Mutiny.StatelessSession session, Scope scope, UUID resource, boolean owner, boolean lock) {
        id(resource);
        String ownership = owner ? " and exists (select 1 from party.involvedpartyxresourceitem o"
                + " join classification.classification oc on oc.classificationid=o.classificationid"
                + " where o.resourceitemid=r.resourceitemid and o.involvedpartyid=:actor and " + owned("o")
                + " and " + role("oc", DocumentTaxonomy.Owner) + ")" : "";
        return bind(session.createNativeQuery("select r.resourceitemid" + documentWhere() + " and r.resourceitemid=:id" + ownership
                        + (lock ? " for update of r" : ""), UUID.class), scope)
                .setParameter("id", resource).setMaxResults(1).getResultList().chain(rows -> rows.isEmpty()
                        ? Uni.createFrom().failure(new NotFoundException()) : Uni.createFrom().voidItem());
    }
    private Uni<UUID> definition(Mutiny.StatelessSession session, Scope scope, boolean resource) {
        String table = resource ? "resource.resourceitemtype" : "arrangement.arrangementtype";
        String key = resource ? "resourceitemtypeid" : "arrangementtypeid";
        String name = resource ? "resourceitemtypename" : "arrangementtypename";
        return session.createNativeQuery("select x." + key + " from " + table + " x where " + owned("x") + " and x." + name + "=:name", UUID.class)
                .setParameter("enterprise", scope.enterprise()).setParameter("system", scope.system().getId())
                .setParameter("name", resource ? DocumentTaxonomy.RESOURCE_TYPE : DocumentTaxonomy.BUCKET_TYPE).getResultList()
                .map(rows -> {
                    if (rows.size() != 1) throw new IllegalStateException("Document type unavailable");
                    return rows.getFirst();
                });
    }

    @Override public Uni<Bucket> createBucket(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, CreateBucket request) {
        if (request == null || request.kind() == null) throw new BadRequestException("Bucket kind required");
        String name = text(request.name(), "Bucket name", 150);
        UUID bucket = UUID.randomUUID();
        return actor(session, system, identity, "createallowed").chain(scope -> definition(session, scope, false).chain(type ->
                insert(session, scope, "arrangement.arrangement", "arrangementid", bucket, Map.of())
                        .chain(() -> insert(session, scope, "arrangement.arrangementxarrangementtype", "arrangementxarrangementtypeid", UUID.randomUUID(),
                                Map.of("arrangementid", bucket, "arrangementtypeid", type, "classificationid", scope.role(DocumentTaxonomy.BucketType), "value", "1")))
                        .chain(() -> property(session, scope, false, bucket, DocumentTaxonomy.BucketContext, scope.context()))
                        .chain(() -> property(session, scope, false, bucket, DocumentTaxonomy.BucketName, name))
                        .chain(() -> property(session, scope, false, bucket, DocumentTaxonomy.BucketKind, request.kind().name()))
                        .chain(() -> membership(session, scope, bucket, scope.actor(), "OWNER"))
                        .replaceWith(new Bucket(bucket, name, request.kind(), identity.context().realm(), identity.context().ownerId()))));
    }
    private Uni<Void> membership(Mutiny.StatelessSession session, Scope scope, UUID bucket, UUID party, String access) {
        return insert(session, scope, "arrangement.arrangementxinvolvedparty", "arrangementxinvolvedpartyid", UUID.randomUUID(),
                Map.of("arrangementid", bucket, "involvedpartyid", party, "classificationid", scope.role(DocumentTaxonomy.BucketMember), "value", access));
    }
    private Uni<List<Bucket>> buckets(Mutiny.StatelessSession session, Scope scope, List<UUID> ids) {
        if (ids.isEmpty()) return Uni.createFrom().item(List.of());
        return session.createNativeQuery("select x.arrangementid,c.classificationname,x.value from arrangement.arrangementxclassification x"
                        + " join classification.classification c on c.classificationid=x.classificationid where x.arrangementid in (:ids)"
                        + " and " + owned("x") + " and " + owned("c") + " and c.classificationname in ('DocumentBucketName','DocumentBucketKind','DocumentBucketContext')", Object[].class)
                .setParameter("ids", ids).setParameter("system", scope.system().getId()).setParameter("enterprise", scope.enterprise())
                .getResultList().map(rows -> {
                    Map<UUID, List<Object[]>> properties = byTarget(rows);
                    return ids.stream().map(bucket -> {
                        Map<String, String> values = new HashMap<>();
                        properties.getOrDefault(bucket, List.of()).forEach(r -> values.put((String) r[1], (String) r[2]));
                        String[] context = required(values, "DocumentBucketContext").split(":", 2);
                        return new Bucket(bucket, required(values, "DocumentBucketName"), BucketKind.valueOf(required(values, "DocumentBucketKind")),
                                ActivityScope.Realm.valueOf(context[0]), UUID.fromString(context[1]));
                    }).toList();
                });
    }
    private static Map<UUID, List<Object[]>> byTarget(List<Object[]> rows) {
        return rows.stream().collect(Collectors.groupingBy(row -> (UUID) row[0]));
    }
    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) throw new IllegalStateException("Document property unavailable: " + key);
        return value;
    }
    @Override public Uni<Bucket> findBucket(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID bucket) {
        return actor(session, system, identity, "readallowed").chain(scope -> bucket(session, scope, bucket, "READER", false)
                .chain(() -> buckets(session, scope, List.of(bucket))).map(List::getFirst));
    }
    @Override public Uni<Page<Bucket>> listBuckets(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID parent, int offset, int limit) {
        page(offset, limit);
        return actor(session, system, identity, "readallowed").chain(scope -> {
            Uni<Void> accessible = parent == null ? Uni.createFrom().voidItem() : bucket(session, scope, parent, "READER", false);
            String child = parent == null ? "" : " and exists (select 1 from arrangement.arrangementxarrangement link"
                    + " join classification.classification lc on lc.classificationid=link.classificationid where link.parentarrangementid=:parent"
                    + " and link.childarrangementid=a.arrangementid and " + owned("link") + " and " + role("lc", DocumentTaxonomy.BucketChild) + ")";
            return accessible.chain(() -> {
                var query = bind(session.createNativeQuery("select distinct a.arrangementid" + bucketWhere()
                        + " and m.value in ('OWNER','EDITOR','READER')" + child + " order by a.arrangementid", UUID.class), scope);
                if (parent != null) query.setParameter("parent", parent);
                return query.setFirstResult(offset).setMaxResults(limit + 1).getResultList()
                        .chain(ids -> buckets(session, scope, ids.stream().limit(limit).toList())
                                .map(items -> new Page<>(items, offset, limit, ids.size() > limit)));
            });
        });
    }

    @Override public Uni<List<Member>> members(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID bucket) {
        return actor(session, system, identity, "readallowed").chain(scope -> bucket(session, scope, bucket, "READER", false)
                .chain(() -> members(session, scope, bucket)));
    }
    private Uni<List<Member>> members(Mutiny.StatelessSession session, Scope scope, UUID bucket) {
        return session.createNativeQuery("select m.involvedpartyid,m.value from arrangement.arrangementxinvolvedparty m"
                        + " join classification.classification c on c.classificationid=m.classificationid"
                        + " where m.arrangementid=:id and " + owned("m") + " and " + role("c", DocumentTaxonomy.BucketMember) + " order by m.involvedpartyid", Object[].class)
                .setParameter("id", bucket).setParameter("enterprise", scope.enterprise()).setParameter("system", scope.system().getId())
                .getResultList().map(rows -> rows.stream().map(r -> new Member((UUID) r[0], (String) r[1])).toList());
    }
    @Override public Uni<Void> grant(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID bucket, UUID party, Grant request) {
        id(party);
        if (request == null || request.access() == null) throw new BadRequestException("Access required");
        return actor(session, system, identity, "updateallowed").chain(scope -> bucket(session, scope, bucket, "OWNER", true)
                .chain(() -> buckets(session, scope, List.of(bucket))).chain(found -> {
                    if (found.getFirst().realm() == ActivityScope.Realm.PERSONAL && !party.equals(scope.actor()))
                        return Uni.createFrom().failure(new BadRequestException("Personal buckets cannot be shared"));
                    return session.createNativeQuery("select p.involvedpartyid from party.involvedparty p where p.involvedpartyid=:party and " + live("p")
                                    + " and exists (select 1 from party.involvedpartyorganic o where o.involvedpartyorganicid=p.involvedpartyid and " + live("o") + ")", UUID.class)
                            .setParameter("party", party).setParameter("enterprise", scope.enterprise()).getResultList()
                            .chain(parties -> parties.isEmpty() ? Uni.createFrom().failure(new BadRequestException("Member must be a live organic party"))
                                    : members(session, scope, bucket)).chain(members -> {
                                Optional<Member> existing = members.stream().filter(m -> party.equals(m.partyId())).findFirst();
                                if (existing.isPresent() && existing.get().access().equals("OWNER"))
                                    return Uni.createFrom().failure(new BadRequestException("Bucket owner cannot be changed"));
                                if (existing.isPresent() && existing.get().access().equals(request.access().name())) return Uni.createFrom().voidItem();
                                if (existing.isEmpty() && members.size() >= MAX_MEMBERS) throw new BadRequestException("At most 100 bucket members");
                                return expire(session, scope, "arrangement.arrangementxinvolvedparty", "x.arrangementid=:id and x.involvedpartyid=:party and x.classificationid=:role",
                                                Map.of("id", bucket, "party", party, "role", scope.role(DocumentTaxonomy.BucketMember)))
                                        .chain(() -> membership(session, scope, bucket, party, request.access().name()));
                            });
                }));
    }
    @Override public Uni<Void> revoke(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID bucket, UUID party) {
        id(party);
        return actor(session, system, identity, "updateallowed").chain(scope -> bucket(session, scope, bucket, "OWNER", true)
                .chain(() -> members(session, scope, bucket)).chain(members -> {
                    if (members.stream().anyMatch(m -> party.equals(m.partyId()) && m.access().equals("OWNER")))
                        throw new BadRequestException("Bucket owner cannot be removed");
                    return expire(session, scope, "arrangement.arrangementxinvolvedparty", "x.arrangementid=:id and x.involvedpartyid=:party and x.classificationid=:role",
                            Map.of("id", bucket, "party", party, "role", scope.role(DocumentTaxonomy.BucketMember)));
                }));
    }

    private record CheckedUpload(byte[] data, String filename, String mime, Metadata metadata, String hash) { }
    private static CheckedUpload checkedUpload(Upload request) {
        if (request == null) throw new BadRequestException("Upload required");
        byte[] data = request.data();
        if (data == null || data.length > MAX_BYTES) throw new BadRequestException("Document payload must be at most 16 MiB");
        String filename = text(request.filename(), "Filename", 150);
        if (filename.contains("/") || filename.contains("\\") || filename.equals(".") || filename.equals(".."))
            throw new BadRequestException("Filename must not contain a path");
        String mime = text(request.contentType(), "Content type", 150).toLowerCase(Locale.ROOT);
        if (!mime.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) throw new BadRequestException("Content type must be type/subtype");
        Metadata metadata = metadata(request.metadata());
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        return new CheckedUpload(data, filename, mime, metadata, hash);
    }
    @Override public Uni<Document> upload(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID bucket, Upload request) {
        CheckedUpload upload = checkedUpload(request);
        UUID resource = UUID.randomUUID();
        return actor(session, system, identity, "createallowed").chain(scope -> bucket(session, scope, bucket, "EDITOR", true)
                .chain(() -> session.createNativeQuery("select clock_timestamp()", OffsetDateTime.class).getSingleResult()).chain(at ->
                        security.getSecurityToken(session, identity.identityToken(), system, identity.tokens()).chain(token ->
                                resources.createBinaryScopeRestricted(session, DocumentTaxonomy.RESOURCE_TYPE, resource,
                                        DocumentTaxonomy.RESOURCE_TYPE, upload.data(), at,
                                        DocumentTaxonomy.ResourceType.classificationName(), system, token, identity.tokens()))
                                .chain(() -> marker(session, scope, resource, resource, resource, at))
                                .chain(() -> insert(session, scope, "party.involvedpartyxresourceitem", "involvedpartyxresourceitemid", UUID.randomUUID(),
                                        Map.of("involvedpartyid", scope.actor(), "resourceitemid", resource, "classificationid", scope.role(DocumentTaxonomy.Owner), "value", "1")))
                                .chain(() -> contain(session, scope, bucket, resource))
                                .chain(() -> binaryProperties(session, scope, resource, upload, at))
                                .chain(() -> property(session, scope, true, resource, DocumentTaxonomy.RatingSum, "0"))
                                .chain(() -> property(session, scope, true, resource, DocumentTaxonomy.RatingCount, "0"))
                                .chain(() -> property(session, scope, true, resource, DocumentTaxonomy.RatingAverage, "0"))
                                .chain(() -> writeMetadata(session, scope, resource, upload.metadata(), at))
                                .chain(() -> documents(session, scope, List.of(resource))).map(List::getFirst)));
    }
    private Uni<Void> contain(Mutiny.StatelessSession session, Scope scope, UUID bucket, UUID resource) {
        return insert(session, scope, "arrangement.arrangementxresourceitem", "arrangementxresourceitemid", UUID.randomUUID(),
                Map.of("arrangementid", bucket, "resourceitemid", resource, "classificationid", scope.role(DocumentTaxonomy.BucketDocument), "value", "1"));
    }
    private Uni<Void> writeMetadata(Mutiny.StatelessSession session, Scope scope, UUID resource, Metadata metadata, OffsetDateTime at) {
        Uni<Void> work = property(session, scope, true, resource, DocumentTaxonomy.Title, metadata.title(), at);
        for (String category : metadata.categories()) work = work.chain(() -> property(session, scope, true, resource, DocumentTaxonomy.Category, category, at));
        for (String label : metadata.labels()) work = work.chain(() -> property(session, scope, true, resource, DocumentTaxonomy.Label, label, at));
        return work;
    }
    private Uni<Void> binaryProperties(Mutiny.StatelessSession session, Scope scope, UUID resource, CheckedUpload upload, OffsetDateTime at) {
        return binaryProperties(session, scope, resource, upload.filename(), upload.mime(), upload.data().length, upload.hash(), at);
    }
    private Uni<Void> binaryProperties(Mutiny.StatelessSession session, Scope scope, UUID resource, String filename, String mime, long size, String hash, OffsetDateTime at) {
        return property(session, scope, true, resource, DocumentTaxonomy.Filename, filename, at)
                .chain(() -> property(session, scope, true, resource, DocumentTaxonomy.ContentType, mime, at))
                .chain(() -> property(session, scope, true, resource, DocumentTaxonomy.Size, Long.toString(size), at))
                .chain(() -> property(session, scope, true, resource, DocumentTaxonomy.Sha256, hash, at));
    }
    private Uni<List<Document>> documents(Mutiny.StatelessSession session, Scope scope, List<UUID> ids) {
        if (ids.isEmpty()) return Uni.createFrom().item(List.of());
        return session.createNativeQuery("select x.resourceitemid,c.classificationname,x.value,coalesce(d.resourceitemxclassificationid,x.resourceitemid) from resource.resourceitemxclassification x"
                        + " join classification.classification c on c.classificationid=x.classificationid"
                        + " left join resource.resourceitemxclassification d on d.resourceitemid=x.resourceitemid and d.classificationid=:versionRole and " + owned("d")
                        + " where x.resourceitemid in (:ids) and " + owned("x") + " and " + owned("c"), Object[].class)
                .setParameter("ids", ids).setParameter("versionRole", scope.role(DocumentTaxonomy.Version))
                .setParameter("enterprise", scope.enterprise()).setParameter("system", scope.system().getId())
                .getResultList().chain(properties -> session.createNativeQuery("select x.resourceitemid,x.value from party.involvedpartyxresourceitem x"
                                + " join classification.classification c on c.classificationid=x.classificationid where x.resourceitemid in (:ids)"
                                + " and x.involvedpartyid=:actor and " + owned("x") + " and " + role("c", DocumentTaxonomy.Rating), Object[].class)
                        .setParameter("ids", ids).setParameter("actor", scope.actor()).setParameter("enterprise", scope.enterprise())
                        .setParameter("system", scope.system().getId()).getResultList().map(ratings -> {
                            Map<UUID, List<Object[]>> grouped = byTarget(properties), groupedRatings = byTarget(ratings);
                            return ids.stream().map(resource -> {
                                List<Object[]> resourceProperties = grouped.getOrDefault(resource, List.of());
                                Map<String, String> values = new HashMap<>();
                                List<String> categories = new ArrayList<>(), labels = new ArrayList<>();
                                resourceProperties.forEach(p -> {
                                    if (p[1].equals("DocumentCategory")) categories.add((String) p[2]);
                                    else if (p[1].equals("DocumentLabel")) labels.add((String) p[2]);
                                    else values.put((String) p[1], (String) p[2]);
                                });
                                Integer mine = groupedRatings.getOrDefault(resource, List.of()).stream().map(r -> Integer.valueOf((String) r[1])).findFirst().orElse(null);
                                return new Document(resource, DocumentTaxonomy.RESOURCE_TYPE, required(values, "DocumentFilename"), required(values, "DocumentContentType"),
                                        Long.parseLong(required(values, "DocumentSize")), required(values, "DocumentSha256"), required(values, "DocumentTitle"), categories.stream().sorted().toList(), labels.stream().sorted().toList(),
                                        Double.parseDouble(required(values, "DocumentRatingAverage")), Long.parseLong(required(values, "DocumentRatingCount")), mine,
                                        (UUID) resourceProperties.getFirst()[3]);
                            }).toList();
                        }));
    }
    @Override public Uni<Document> find(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource) {
        return actor(session, system, identity, "readallowed").chain(scope -> document(session, scope, resource, false, false)
                .chain(() -> documents(session, scope, List.of(resource))).map(List::getFirst));
    }
    @Override public Uni<Page<Document>> list(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity,
                                              UUID bucket, Filter filter, int offset, int limit) {
        page(offset, limit);
        Filter checked = filter == null ? new Filter(null, null, null) : new Filter(
                filter.category() == null ? null : text(filter.category(), "Category", 150),
                filter.label() == null ? null : text(filter.label(), "Label", 150),
                filter.search() == null ? null : text(filter.search(), "Search", 150));
        return actor(session, system, identity, "readallowed").chain(scope -> bucket(session, scope, bucket, "READER", false).chain(() -> {
            // Authorize this bucket once in the same statement, before expanding its contents.
            String sql = "with accessible_bucket as materialized (select a.arrangementid" + bucketWhere()
                    + " and a.arrangementid=:bucket and m.value in ('OWNER','EDITOR','READER') limit 1)"
                    + " select r.resourceitemid,r.warehousecreatedtimestamp" + resourceWhere()
                    + " and exists (select 1 from arrangement.arrangementxresourceitem link"
                    + " join accessible_bucket a on a.arrangementid=link.arrangementid"
                    + " join classification.classification lc on lc.classificationid=link.classificationid"
                    + " where link.resourceitemid=r.resourceitemid and " + owned("link")
                    + " and " + role("lc", DocumentTaxonomy.BucketDocument) + ")";
            if (checked.category() != null) sql += filterProperty(DocumentTaxonomy.Category, "category", false);
            if (checked.label() != null) sql += filterProperty(DocumentTaxonomy.Label, "label", false);
            if (checked.search() != null) sql += filterProperty(DocumentTaxonomy.Title, "search", true);
            var query = bind(session.createNativeQuery(sql + " order by r.warehousecreatedtimestamp,r.resourceitemid", Object[].class), scope).setParameter("bucket", bucket);
            if (checked.category() != null) query.setParameter("category", checked.category());
            if (checked.label() != null) query.setParameter("label", checked.label());
            if (checked.search() != null) query.setParameter("search", checked.search());
            return query.setFirstResult(offset).setMaxResults(limit + 1).getResultList().chain(rows ->
                    documents(session, scope, rows.stream().limit(limit).map(r -> (UUID) r[0]).toList())
                            .map(items -> new Page<>(items, offset, limit, rows.size() > limit)));
        }));
    }
    private static String filterProperty(DocumentTaxonomy taxonomy, String parameter, boolean search) {
        return " and exists (select 1 from resource.resourceitemxclassification p join classification.classification pc on pc.classificationid=p.classificationid"
                + " where p.resourceitemid=r.resourceitemid and " + owned("p") + " and " + role("pc", taxonomy)
                + (search ? " and strpos(lower(p.value),lower(:" + parameter + "))>0)" : " and p.value=:" + parameter + ")");
    }
    private static String historical(String a) {
        return a + ".enterpriseid=:enterprise and " + a + ".systemid=:system and " + a + ".effectivefromdate<=statement_timestamp()"
                + " and " + allowed(a);
    }
    private record Head(UUID id, UUID payload) { }
    private Uni<Void> marker(Mutiny.StatelessSession session, Scope scope, UUID resource, UUID version, UUID payload, OffsetDateTime at) {
        return insert(session, scope, "resource.resourceitemxclassification", "resourceitemxclassificationid", version,
                Map.of("resourceitemid", resource, "classificationid", scope.role(DocumentTaxonomy.Version), "value", payload.toString()), at);
    }
    private Uni<Head> head(Mutiny.StatelessSession session, Scope scope, UUID resource) {
        return session.createNativeQuery("select d.resourceitemxclassificationid,cast(d.value as uuid) from resource.resourceitemxclassification d"
                        + " where d.resourceitemid=:id and d.classificationid=:role and " + owned("d"), Object[].class)
                .setParameter("id", resource).setParameter("role", scope.role(DocumentTaxonomy.Version))
                .setParameter("enterprise", scope.enterprise()).setParameter("system", scope.system().getId()).getResultList().chain(rows -> {
                    if (rows.size() > 1) throw new IllegalStateException("Multiple current document revisions");
                    if (!rows.isEmpty()) return Uni.createFrom().item(new Head((UUID) rows.getFirst()[0], (UUID) rows.getFirst()[1]));
                    // A migrated legacy payload is addressed directly by the stable resource ID.
                    // Seed its first version only within a caller-owned write transaction and resource lock.
                    return session.createNativeQuery("select r.effectivefromdate from resource.resourceitem r where r.resourceitemid=:id and " + owned("r"), OffsetDateTime.class)
                            .setParameter("id", resource).setParameter("enterprise", scope.enterprise()).setParameter("system", scope.system().getId()).getSingleResult()
                            .chain(at -> marker(session, scope, resource, resource, resource, at).replaceWith(new Head(resource, resource)));
                });
    }
    private static void expected(Head head, UUID expected) {
        if (!head.id().equals(id(expected))) throw new ClientErrorException("Document version changed; reload the current document", 409);
    }
    private Uni<OffsetDateTime> boundary(Mutiny.StatelessSession session, Scope scope, UUID resource) {
        // One database timestamp, after acquiring the resource lock, closes and opens every SCD row.
        return session.createNativeQuery("select greatest(clock_timestamp(),d.effectivefromdate + interval '1 microsecond')"
                        + " from resource.resourceitemxclassification d where d.resourceitemid=:id and d.classificationid=:role and " + owned("d"), OffsetDateTime.class)
                .setParameter("id", resource).setParameter("role", scope.role(DocumentTaxonomy.Version))
                .setParameter("enterprise", scope.enterprise()).setParameter("system", scope.system().getId()).getSingleResult();
    }
    private Uni<Void> advance(Mutiny.StatelessSession session, Scope scope, UUID resource, Head head, UUID version, UUID payload, OffsetDateTime at) {
        return expireAt(session, scope, "resource.resourceitemxclassification", "x.resourceitemxclassificationid=:version and x.resourceitemid=:id and x.classificationid=:role",
                        Map.of("version", head.id(), "id", resource, "role", scope.role(DocumentTaxonomy.Version)), at)
                .chain(() -> marker(session, scope, resource, version, payload, at));
    }
    private Uni<Void> replaceRevision(Mutiny.StatelessSession session, Scope scope, UUID resource, Head head, UUID version, UUID payload,
                                      String filename, String mime, long size, String hash, Metadata metadata, OffsetDateTime at) {
        List<UUID> roles = List.of(DocumentTaxonomy.Filename, DocumentTaxonomy.ContentType, DocumentTaxonomy.Size, DocumentTaxonomy.Sha256,
                DocumentTaxonomy.Title, DocumentTaxonomy.Category, DocumentTaxonomy.Label).stream().map(scope::role).toList();
        return advance(session, scope, resource, head, version, payload, at)
                .chain(() -> expireAt(session, scope, "resource.resourceitemxclassification", "x.resourceitemid=:id and x.classificationid in (:roles)",
                        Map.of("id", resource, "roles", roles), at))
                .chain(() -> binaryProperties(session, scope, resource, filename, mime, size, hash, at))
                .chain(() -> writeMetadata(session, scope, resource, metadata, at));
    }
    @Override public Uni<Document> revise(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource, Revise request) {
        if (request == null) throw new BadRequestException("Revision required");
        id(request.expectedVersionId());
        CheckedUpload upload = checkedUpload(request.upload());
        UUID version = UUID.randomUUID();
        return actor(session, system, identity, "updateallowed").chain(scope -> document(session, scope, resource, true, true)
                .chain(() -> head(session, scope, resource)).invoke(head -> expected(head, request.expectedVersionId()))
                .chain(head -> boundary(session, scope, resource).chain(at ->
                        resources.storeResourceDataValue(session, version, upload.data())
                                .chain(() -> replaceRevision(session, scope, resource, head, version, version,
                                        upload.filename(), upload.mime(), upload.data().length, upload.hash(), upload.metadata(), at))))
                .chain(() -> documents(session, scope, List.of(resource))).map(List::getFirst));
    }
    private static OffsetDateTime date(Object value) {
        if (value instanceof OffsetDateTime at) return at;
        if (value instanceof java.time.ZonedDateTime at) return at.toOffsetDateTime();
        if (value instanceof java.sql.Timestamp at) return at.toInstant().atOffset(ZoneOffset.UTC);
        throw new IllegalStateException("Unsupported SCD timestamp type");
    }
    private Uni<List<Object[]>> revisionRows(Mutiny.StatelessSession session, Scope scope, UUID resource, UUID version, int offset, int limit) {
        // The second branch projects pre-versioning resources without touching deprecated ResourceItemData.
        String sql = "select d.resourceitemxclassificationid,d.effectivefromdate,d.effectivetodate,d.effectivetodate>statement_timestamp(),"
                + " cast(d.value as uuid),least(d.effectivetodate - interval '1 microsecond',statement_timestamp())"
                + " from resource.resourceitemxclassification d where d.resourceitemid=:id and d.classificationid=:role and " + historical("d")
                + (version == null ? "" : " and d.resourceitemxclassificationid=:version")
                + " union all select r.resourceitemid,r.effectivefromdate,r.effectivetodate,true,r.resourceitemid,statement_timestamp()"
                + " from resource.resourceitem r where r.resourceitemid=:id and " + owned("r")
                + " and not exists (select 1 from resource.resourceitemxclassification d where d.resourceitemid=r.resourceitemid"
                + " and d.classificationid=:role and d.enterpriseid=:enterprise and d.systemid=:system)"
                + (version == null ? "" : " and r.resourceitemid=:version")
                + " order by 2 desc,1 limit :limit offset :offset";
        var query = session.createNativeQuery(sql, Object[].class).setParameter("id", resource).setParameter("role", scope.role(DocumentTaxonomy.Version))
                .setParameter("enterprise", scope.enterprise()).setParameter("system", scope.system().getId()).setParameter("offset", offset).setParameter("limit", limit);
        if (version != null) query.setParameter("version", version);
        return query.getResultList();
    }
    private Uni<List<Version>> snapshots(Mutiny.StatelessSession session, Scope scope, UUID resource, List<Object[]> rows) {
        if (rows.isEmpty()) return Uni.createFrom().item(List.of());
        String instants = java.util.stream.IntStream.range(0, rows.size())
                .mapToObj(i -> "(cast(:version" + i + " as uuid),cast(:at" + i + " as timestamptz))").collect(Collectors.joining(","));
        var query = session.createNativeQuery("with revisions(id,at) as (values " + instants + ")"
                        + " select d.id,c.classificationname,p.value from revisions d"
                        + " join resource.resourceitemxclassification p on p.resourceitemid=:id"
                        + " join classification.classification c on c.classificationid=p.classificationid"
                        + " where " + historical("p") + " and " + owned("c")
                        + " and p.effectivefromdate<=d.at and p.effectivetodate>d.at order by p.value", Object[].class)
                .setParameter("id", resource).setParameter("enterprise", scope.enterprise()).setParameter("system", scope.system().getId());
        for (int i = 0; i < rows.size(); i++) query.setParameter("version" + i, rows.get(i)[0]).setParameter("at" + i, date(rows.get(i)[5]));
        return query.getResultList().map(properties -> {
            Map<UUID, List<Object[]>> grouped = byTarget(properties);
            return rows.stream().map(row -> {
            Map<String, String> values = new HashMap<>();
            List<String> categories = new ArrayList<>(), labels = new ArrayList<>();
            grouped.getOrDefault((UUID) row[0], List.of()).forEach(p -> {
                if (p[1].equals("DocumentCategory")) categories.add((String) p[2]);
                else if (p[1].equals("DocumentLabel")) labels.add((String) p[2]);
                else values.put((String) p[1], (String) p[2]);
            });
            return new Version((UUID) row[0], resource, DocumentTaxonomy.RESOURCE_TYPE, date(row[1]), date(row[2]), (Boolean) row[3],
                    required(values, "DocumentFilename"), required(values, "DocumentContentType"), Long.parseLong(required(values, "DocumentSize")),
                    required(values, "DocumentSha256"), required(values, "DocumentTitle"), categories.stream().sorted().toList(), labels.stream().sorted().toList());
        }).toList(); });
    }
    private Uni<byte[]> payload(Mutiny.StatelessSession session, List<Object[]> rows) {
        if (rows.isEmpty()) return Uni.createFrom().failure(new NotFoundException());
        return resources.getResourceDataValue(session, (UUID) rows.getFirst()[4]).map(value -> {
                    if (value == null) throw new NotFoundException();
                    return value;
                });
    }
    @Override public Uni<Page<Version>> versions(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource, int offset, int limit) {
        page(offset, limit);
        return actor(session, system, identity, "readallowed").chain(scope -> document(session, scope, resource, false, false)
                .chain(() -> revisionRows(session, scope, resource, null, offset, limit + 1))
                .chain(rows -> snapshots(session, scope, resource, rows.stream().limit(limit).toList())
                        .map(items -> new Page<>(items, offset, limit, rows.size() > limit))));
    }
    @Override public Uni<Version> version(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource, UUID version) {
        id(version);
        return actor(session, system, identity, "readallowed").chain(scope -> document(session, scope, resource, false, false)
                .chain(() -> revisionRows(session, scope, resource, version, 0, 1))
                .chain(rows -> rows.isEmpty() ? Uni.createFrom().failure(new NotFoundException())
                        : snapshots(session, scope, resource, rows).map(List::getFirst)));
    }
    @Override public Uni<Content> downloadVersion(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource, UUID version) {
        id(version);
        return actor(session, system, identity, "readallowed").chain(scope -> document(session, scope, resource, false, false)
                .chain(() -> revisionRows(session, scope, resource, version, 0, 1))
                .chain(rows -> snapshots(session, scope, resource, rows).chain(versions -> payload(session, rows)
                        .map(bytes -> new Content(versions.getFirst().filename(), versions.getFirst().contentType(), bytes)))));
    }
    @Override public Uni<Document> restoreVersion(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource, UUID version, VersionExpectation request) {
        id(version);
        if (request == null) throw new BadRequestException("Current version required");
        id(request.expectedVersionId());
        return actor(session, system, identity, "updateallowed").chain(scope -> document(session, scope, resource, true, true)
                .chain(() -> head(session, scope, resource)).invoke(head -> expected(head, request.expectedVersionId()))
                .chain(head -> revisionRows(session, scope, resource, version, 0, 1).chain(rows -> {
                    if (rows.isEmpty()) return Uni.createFrom().failure(new NotFoundException());
                    return snapshots(session, scope, resource, rows).chain(versions -> boundary(session, scope, resource).chain(at -> {
                        Version previous = versions.getFirst();
                        return replaceRevision(session, scope, resource, head, UUID.randomUUID(), (UUID) rows.getFirst()[4],
                                previous.filename(), previous.contentType(), previous.size(), previous.sha256(),
                                new Metadata(previous.title(), previous.categories(), previous.labels()), at);
                    }));
                }))
                .chain(() -> documents(session, scope, List.of(resource))).map(List::getFirst));
    }
    @Override public Uni<Content> download(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource) {
        return actor(session, system, identity, "readallowed").chain(scope -> document(session, scope, resource, false, false)
                .chain(() -> documents(session, scope, List.of(resource))).chain(documents -> {
                    Document doc = documents.getFirst();
                    return revisionRows(session, scope, resource, doc.versionId(), 0, 1).chain(rows -> payload(session, rows)
                            .map(bytes -> new Content(doc.filename(), doc.contentType(), bytes)));
                }));
    }
    @Override public Uni<Document> updateMetadata(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource, Metadata request) {
        Metadata metadata = metadata(request);
        return actor(session, system, identity, "updateallowed").chain(scope -> document(session, scope, resource, true, true)
                .chain(() -> head(session, scope, resource)).chain(head -> boundary(session, scope, resource).chain(at ->
                    advance(session, scope, resource, head, UUID.randomUUID(), head.payload(), at)
                        .chain(() -> expireAt(session, scope, "resource.resourceitemxclassification", "x.resourceitemid=:id and x.classificationid in (:roles)",
                                Map.of("id", resource, "roles", List.of(scope.role(DocumentTaxonomy.Title), scope.role(DocumentTaxonomy.Category), scope.role(DocumentTaxonomy.Label))), at))
                        .chain(() -> writeMetadata(session, scope, resource, metadata, at))))
                .chain(() -> documents(session, scope, List.of(resource))).map(List::getFirst));
    }
    @Override public Uni<Void> addToBucket(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID bucket, UUID resource) {
        return actor(session, system, identity, "updateallowed").chain(scope -> bucket(session, scope, bucket, "EDITOR", true)
                .chain(() -> document(session, scope, resource, true, true))
                // A resource cannot cross Personal/Social/Work contexts through a bucket link.
                .chain(() -> session.createNativeQuery("select distinct cx.value" + bucketWhere()
                                + " and exists (select 1 from arrangement.arrangementxresourceitem l where l.arrangementid=a.arrangementid"
                                + " and l.resourceitemid=:resource and l.classificationid=:role and " + owned("l") + ")", String.class)
                        .setParameter("role", scope.role(DocumentTaxonomy.BucketDocument)).setParameter("resource", resource)
                        .setParameter("enterprise", scope.enterprise()).setParameter("system", scope.system().getId())
                        .setParameter("context", scope.context()).setParameter("actor", scope.actor())
                        .setParameter("social", scope.identity().context().realm() == ActivityScope.Realm.SOCIAL).getResultList())
                .chain(contexts -> buckets(session, scope, List.of(bucket)).chain(target -> {
                    Bucket b = target.getFirst();
                    if (contexts.size() != 1 || !contexts.getFirst().equals(b.realm().name() + ":" + b.ownerId()))
                        throw new BadRequestException("Buckets must belong to the same context");
                    return session.createNativeQuery("select 1 from arrangement.arrangementxresourceitem l where l.arrangementid=:bucket and l.resourceitemid=:resource"
                                    + " and l.classificationid=:role and " + owned("l"), Integer.class)
                            .setParameter("bucket", bucket).setParameter("resource", resource).setParameter("role", scope.role(DocumentTaxonomy.BucketDocument))
                            .setParameter("system", scope.system().getId()).setParameter("enterprise", scope.enterprise()).getResultList()
                            .chain(existing -> existing.isEmpty() ? contain(session, scope, bucket, resource) : Uni.createFrom().voidItem());
                })));
    }
    @Override public Uni<Void> removeFromBucket(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID bucket, UUID resource) {
        return actor(session, system, identity, "updateallowed").chain(scope -> bucket(session, scope, bucket, "EDITOR", true)
                .chain(() -> document(session, scope, resource, true, true))
                .chain(() -> session.createNativeQuery("select l.arrangementid from arrangement.arrangementxresourceitem l where l.resourceitemid=:resource"
                                + " and l.classificationid=:role and " + owned("l"), UUID.class)
                        .setParameter("resource", resource).setParameter("role", scope.role(DocumentTaxonomy.BucketDocument))
                        .setParameter("system", scope.system().getId()).setParameter("enterprise", scope.enterprise()).getResultList())
                .chain(containers -> {
                    if (!containers.contains(bucket)) return Uni.createFrom().voidItem();
                    if (containers.size() == 1) throw new BadRequestException("Document must remain in a bucket; archive it to remove it");
                    return expire(session, scope, "arrangement.arrangementxresourceitem", "x.arrangementid=:bucket and x.resourceitemid=:resource and x.classificationid=:role",
                            Map.of("bucket", bucket, "resource", resource, "role", scope.role(DocumentTaxonomy.BucketDocument)));
                }));
    }
    @Override public Uni<Void> archive(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource) {
        return actor(session, system, identity, "updateallowed").chain(scope -> document(session, scope, resource, true, true)
                .chain(() -> expire(session, scope, "resource.resourceitem", "x.resourceitemid=:id", Map.of("id", resource)))
                .chain(() -> expire(session, scope, "resource.resourceitemxclassification", "x.resourceitemid=:id and x.classificationid=:role",
                        Map.of("id", resource, "role", scope.role(DocumentTaxonomy.Version))))
                .chain(() -> expire(session, scope, "arrangement.arrangementxresourceitem", "x.resourceitemid=:id and x.classificationid=:role",
                        Map.of("id", resource, "role", scope.role(DocumentTaxonomy.BucketDocument)))));
    }

    @Override public Uni<Document> rate(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource, Rate request) {
        if (request == null || request.score() < 1 || request.score() > 5) throw new BadRequestException("Rating must be 1..5");
        return changeRating(session, system, identity, resource, request.score());
    }
    @Override public Uni<Document> clearRating(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource) {
        return changeRating(session, system, identity, resource, null);
    }
    private Uni<Document> changeRating(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID resource, Integer score) {
        return actor(session, system, identity, "updateallowed").chain(scope -> document(session, scope, resource, false, true)
                .chain(() -> documents(session, scope, List.of(resource))).chain(current -> {
                    Document doc = current.getFirst();
                    if (Objects.equals(doc.myRating(), score)) return Uni.createFrom().item(doc);
                    return session.createNativeQuery("select x.value from resource.resourceitemxclassification x where x.resourceitemid=:id"
                                    + " and x.classificationid=:role and " + owned("x"), String.class)
                            .setParameter("id", resource).setParameter("role", scope.role(DocumentTaxonomy.RatingSum))
                            .setParameter("system", scope.system().getId()).setParameter("enterprise", scope.enterprise()).getSingleResult()
                            .chain(sumValue -> {
                                long sum = Long.parseLong(sumValue) - (doc.myRating() == null ? 0 : doc.myRating()) + (score == null ? 0 : score);
                                long count = doc.ratingCount() + (doc.myRating() == null ? 0 : -1) + (score == null ? 0 : 1);
                                Uni<Void> work = expire(session, scope, "party.involvedpartyxresourceitem", "x.resourceitemid=:id and x.involvedpartyid=:actor and x.classificationid=:role",
                                        Map.of("id", resource, "actor", scope.actor(), "role", scope.role(DocumentTaxonomy.Rating)));
                                if (score != null) work = work.chain(() -> insert(session, scope, "party.involvedpartyxresourceitem", "involvedpartyxresourceitemid", UUID.randomUUID(),
                                        Map.of("resourceitemid", resource, "involvedpartyid", scope.actor(), "classificationid", scope.role(DocumentTaxonomy.Rating), "value", score.toString())));
                                return work.chain(() -> summary(session, scope, resource, DocumentTaxonomy.RatingSum, Long.toString(sum)))
                                        .chain(() -> summary(session, scope, resource, DocumentTaxonomy.RatingCount, Long.toString(count)))
                                        .chain(() -> summary(session, scope, resource, DocumentTaxonomy.RatingAverage, Double.toString(count == 0 ? 0 : (double) sum / count)))
                                        .chain(() -> documents(session, scope, List.of(resource))).map(List::getFirst);
                            });
                }));
    }
    private Uni<Void> summary(Mutiny.StatelessSession session, Scope scope, UUID resource, DocumentTaxonomy role, String value) {
        return session.createNativeQuery("update resource.resourceitemxclassification x set value=:value,warehouselastupdatedtimestamp=statement_timestamp()"
                        + " where x.resourceitemid=:id and x.classificationid=:role and " + owned("x"))
                .setParameter("id", resource).setParameter("role", scope.role(role)).setParameter("value", value)
                .setParameter("system", scope.system().getId()).setParameter("enterprise", scope.enterprise()).executeUpdate().invoke(changed -> {
                    if (changed != 1) throw new IllegalStateException("Rating summary unavailable");
                }).replaceWithVoid();
    }

    private Uni<Void> arrangementLink(Mutiny.StatelessSession session, Scope scope, UUID parent, UUID child, DocumentTaxonomy role, boolean remove) {
        if (remove) return expire(session, scope, "arrangement.arrangementxarrangement", "x.parentarrangementid=:parent and x.childarrangementid=:child and x.classificationid=:role",
                Map.of("parent", parent, "child", child, "role", scope.role(role)));
        return session.createNativeQuery("select 1 from arrangement.arrangementxarrangement x where x.parentarrangementid=:parent and x.childarrangementid=:child"
                        + " and x.classificationid=:role and " + owned("x"), Integer.class)
                .setParameter("parent", parent).setParameter("child", child).setParameter("role", scope.role(role))
                .setParameter("enterprise", scope.enterprise()).setParameter("system", scope.system().getId()).getResultList()
                .chain(rows -> rows.isEmpty() ? insert(session, scope, "arrangement.arrangementxarrangement", "arrangementxarrangementid", UUID.randomUUID(),
                        Map.of("parentarrangementid", parent, "childarrangementid", child, "classificationid", scope.role(role), "value", "1")) : Uni.createFrom().voidItem());
    }
    @Override public Uni<Void> childBucket(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID parent, UUID child, boolean remove) {
        id(parent); id(child);
        if (parent.equals(child)) throw new BadRequestException("Bucket cannot contain itself");
        return actor(session, system, identity, "updateallowed").chain(scope ->
                // Serialize hierarchy changes across all parents before checking reachability.
                session.createNativeQuery("select systemid from dbo.systems where systemid=:system and enterpriseid=:enterprise for update", UUID.class)
                        .setParameter("system", scope.system().getId()).setParameter("enterprise", scope.enterprise()).getResultList()
                        .chain(() -> bucket(session, scope, parent, "OWNER", true)).chain(() -> bucket(session, scope, child, "OWNER", true))
                        .chain(() -> buckets(session, scope, List.of(parent, child))).chain(found -> {
                            if (found.getFirst().realm() != found.getLast().realm() || !found.getFirst().ownerId().equals(found.getLast().ownerId()))
                                throw new BadRequestException("Buckets must belong to the same context");
                            if (remove) return arrangementLink(session, scope, parent, child, DocumentTaxonomy.BucketChild, true);
                            return session.createNativeQuery("with recursive descendants(id) as (select cast(:child as uuid) union"
                                            + " select x.childarrangementid from arrangement.arrangementxarrangement x join descendants d on x.parentarrangementid=d.id"
                                            + " where x.classificationid=:role and " + owned("x") + ") select id from descendants where id=:parent", UUID.class)
                                    .setParameter("child", child).setParameter("parent", parent).setParameter("role", scope.role(DocumentTaxonomy.BucketChild))
                                    .setParameter("enterprise", scope.enterprise()).setParameter("system", scope.system().getId()).getResultList()
                                    .chain(rows -> rows.isEmpty() ? arrangementLink(session, scope, parent, child, DocumentTaxonomy.BucketChild, false)
                                            : Uni.createFrom().failure(new BadRequestException("Bucket hierarchy cannot contain a cycle")));
                        }));
    }
    @Override public Uni<Void> attachBucket(Mutiny.StatelessSession session, ISystems<?, ?> system, DocumentIdentity identity, UUID arrangement, UUID bucket, boolean remove) {
        id(arrangement); id(bucket);
        if (arrangement.equals(bucket)) throw new BadRequestException("Bucket cannot attach to itself");
        return actor(session, system, identity, "updateallowed").chain(scope -> bucket(session, scope, bucket, "OWNER", true)
                .chain(() -> session.createNativeQuery("select a.arrangementid from arrangement.arrangement a where a.arrangementid=:arrangement and " + live("a")
                                + " and exists (select 1 from arrangement.arrangementsecuritytoken g where g.arrangementid=a.arrangementid"
                                + " and g.systemid=a.systemid and g.securitytokenid in (:tokens) and g.readallowed=1 and g.updateallowed=1 and " + live("g") + ")"
                                + " and exists (select 1 from dbo.systemssecuritytoken g where g.systemid=a.systemid and g.securitytokenid in (:tokens)"
                                + " and g.readallowed=1 and g.updateallowed=1 and " + live("g") + ") for update of a", UUID.class)
                        .setParameter("arrangement", arrangement).setParameter("tokens", scope.tokens()).setParameter("enterprise", scope.enterprise()).getResultList())
                .chain(rows -> rows.isEmpty() ? Uni.createFrom().failure(new NotFoundException())
                        : arrangementLink(session, scope, arrangement, bucket, DocumentTaxonomy.BucketAttachment, remove)));
    }
}
