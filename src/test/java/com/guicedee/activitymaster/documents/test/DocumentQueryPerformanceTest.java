package com.guicedee.activitymaster.documents.test;

import com.guicedee.activitymaster.documents.*;
import com.guicedee.activitymaster.documents.DocumentModels.*;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import com.guicedee.activitymaster.fsdm.db.FsdmSchema;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.images.builder.Transferable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: plans the actual native queries and times real operations on a scaled canonical PostgreSQL database. */
@EnabledIfSystemProperty(named="document.performance", matches="true")
class DocumentQueryPerformanceTest {
    private DocumentTestFixture fixture;
    private IDocumentService service;
    private DocumentIdentity owner;
    private final Map<String, Statement> statements = new LinkedHashMap<>();
    private final Map<String, Double> operations = new LinkedHashMap<>();
    private String operation;
    private record Plan(String text, double planning, double execution) { }
    private static final class Statement {
        String sql, operation;
        Map<String, Object> parameters = new LinkedHashMap<>();
        int offset, limit = Integer.MAX_VALUE;
    }

    private static Object invoke(Method method, Object target, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
    private Mutiny.StatelessSession trace(Mutiny.StatelessSession session) {
        return (Mutiny.StatelessSession) Proxy.newProxyInstance(Mutiny.class.getClassLoader(),
                new Class<?>[]{Mutiny.StatelessSession.class}, (proxy, method, args) -> {
            Object result = invoke(method, session, args);
            if (!method.getName().equals("createNativeQuery")) return result;
            Statement statement = new Statement(); statement.sql = (String) args[0]; statement.operation = operation;
            return Proxy.newProxyInstance(Mutiny.class.getClassLoader(), new Class<?>[]{method.getReturnType()}, (queryProxy, queryMethod, queryArgs) -> {
                String name = queryMethod.getName();
                if (name.equals("setParameter") && queryArgs[0] instanceof String key) statement.parameters.put(key, queryArgs[1]);
                if (name.equals("setFirstResult")) statement.offset = (Integer) queryArgs[0];
                if (name.equals("setMaxResults")) statement.limit = (Integer) queryArgs[0];
                if (name.startsWith("getResult") || name.startsWith("getSingleResult") || name.equals("executeUpdate")) {
                    String key = statement.sql + ":" + statement.limit + ":" + statement.offset;
                    statements.compute(key, (k,previous) -> previous == null || batchSize(statement)>batchSize(previous) ? statement : previous);
                }
                Object answer = invoke(queryMethod, result, queryArgs);
                return answer == result ? queryProxy : answer;
            });
        });
    }
    private static int batchSize(Statement statement) {
        return statement.parameters.values().stream().filter(Collection.class::isInstance)
                .mapToInt(value -> ((Collection<?>)value).size()).sum();
    }
    private <T> T run(String name, BiFunction<Mutiny.StatelessSession, ISystems<?, ?>, Uni<T>> action) {
        operation = name; long start = System.nanoTime();
        T result = fixture.run(c -> action.apply(trace(c.getItem1()), c.getItem3()));
        operations.put(name, (System.nanoTime()-start)/1_000_000.0);
        System.out.println("Document performance operation " + name + ": " + operations.get(name) + " ms");
        return result;
    }
    private static Upload upload(String title) { return new Upload("sample.pdf", "application/pdf", new byte[]{0,1,-1},
            new Metadata(title, List.of("Category-0"), List.of("Label-0"))); }

    @Test void explainDocumentQueriesAtScale() throws Exception {
        fixture = DocumentTestFixture.get(); service = fixture.service;
        assertNotNull(fixture.run(c -> c.getItem1().createNativeQuery("explain (analyze) select 1",String.class).getResultList()));
        String indexes = FsdmSchema.read("23.document-query-indexes.sql");
        // Only this disposable test database: compare the append-only migration on the same data.
        var definitions = java.util.regex.Pattern.compile("CREATE INDEX IF NOT EXISTS (\\w+)\\s+ON (\\w+)\\.").matcher(indexes);
        StringBuilder drop = new StringBuilder();
        while (definitions.find()) drop.append("DROP INDEX IF EXISTS ").append(definitions.group(2)).append('.').append(definitions.group(1)).append(';');
        sql(drop.toString());
        owner = new DocumentIdentity(fixture.actorId, fixture.enterpriseId,
                new ActivityScope.Context(ActivityScope.Realm.WORK, fixture.enterpriseId), fixture.token);
        Bucket bucket = run("createBucket", (s,y) -> service.createBucket(s,y,owner,new CreateBucket("Performance",BucketKind.BUCKET)));
        Bucket other = run("createBucket.other", (s,y) -> service.createBucket(s,y,owner,new CreateBucket("Other",BucketKind.CATEGORY)));
        Document document = run("upload", (s,y) -> service.upload(s,y,owner,bucket.id(),upload("Template")));
        run("rate.seed", (s,y) -> service.rate(s,y,owner,document.id(),new Rate(4)));
        String seed;
        try (var stream = getClass().getResourceAsStream("/performance-seed.sql")) {
            seed = new String(Objects.requireNonNull(stream).readAllBytes(),StandardCharsets.UTF_8);
        }
        sql(seed.replace("@BUCKET@",bucket.id().toString()).replace("@OTHER_BUCKET@",other.id().toString())
                .replace("@DOCUMENT@",document.id().toString()));
        System.out.println("Document performance dataset seeded: 12,000 documents, 96,000 versions, 1,200 buckets, 96,000 membership rows.");
        UUID resource = fixture.run(c -> c.getItem1().createNativeQuery("select md5('performance-document-1')::uuid",UUID.class).getSingleResult());
        final UUID target = resource;
        Document current = run("find", (s,y) -> service.find(s,y,owner,target));
        run("findBucket", (s,y) -> service.findBucket(s,y,owner,bucket.id()));
        run("listBuckets", (s,y) -> service.listBuckets(s,y,owner,null,0,50));
        run("members", (s,y) -> service.members(s,y,owner,bucket.id()));
        assertEquals(50,run("list", (s,y) -> service.list(s,y,owner,bucket.id(),null,0,50)).items().size());
        run("list.offset", (s,y) -> service.list(s,y,owner,bucket.id(),null,2000,50));
        run("list.category", (s,y) -> service.list(s,y,owner,bucket.id(),new Filter("Category-0",null,null),0,50));
        run("list.label", (s,y) -> service.list(s,y,owner,bucket.id(),new Filter(null,"Label-0",null),0,50));
        assertEquals(3,run("list.search", (s,y) -> service.list(s,y,owner,bucket.id(),new Filter(null,null,"Needle"),0,50)).items().size());
        run("list.combined", (s,y) -> service.list(s,y,owner,bucket.id(),new Filter("Category-0","Label-0","Needle"),0,50));
        Page<Version> history = run("versions", (s,y) -> service.versions(s,y,owner,target,0,50));
        assertEquals(8,history.items().size()); UUID old = history.items().getLast().id();
        run("versions.offset", (s,y) -> service.versions(s,y,owner,target,5,2));
        run("version", (s,y) -> service.version(s,y,owner,target,old));
        run("downloadVersion", (s,y) -> service.downloadVersion(s,y,owner,target,old));
        run("download", (s,y) -> service.download(s,y,owner,target));
        Document revised = run("revise", (s,y) -> service.revise(s,y,owner,target,new Revise(current.versionId(),upload("Revised"))));
        run("restoreVersion", (s,y) -> service.restoreVersion(s,y,owner,target,old,new VersionExpectation(revised.versionId())));
        run("metadata", (s,y) -> service.updateMetadata(s,y,owner,target,new Metadata("Metadata",List.of("Category-0"),List.of("Label-0"))));
        run("rate", (s,y) -> service.rate(s,y,owner,target,new Rate(5)));
        run("clearRating", (s,y) -> service.clearRating(s,y,owner,target));
        run("grant", (s,y) -> service.grant(s,y,owner,bucket.id(),fixture.recipientId,new Grant(Access.READER)));
        run("revoke", (s,y) -> service.revoke(s,y,owner,bucket.id(),fixture.recipientId));
        run("childBucket", (s,y) -> service.childBucket(s,y,owner,bucket.id(),other.id(),false));
        run("listBuckets.children", (s,y) -> service.listBuckets(s,y,owner,bucket.id(),0,50));
        run("childBucket.remove", (s,y) -> service.childBucket(s,y,owner,bucket.id(),other.id(),true));
        run("addToBucket", (s,y) -> service.addToBucket(s,y,owner,other.id(),target));
        run("removeFromBucket", (s,y) -> service.removeFromBucket(s,y,owner,other.id(),target));
        UUID arrangement = targetArrangement();
        run("attachBucket", (s,y) -> service.attachBucket(s,y,owner,arrangement,bucket.id(),false));
        run("attachBucket.remove", (s,y) -> service.attachBucket(s,y,owner,arrangement,bucket.id(),true));
        run("archive", (s,y) -> service.archive(s,y,owner,document.id()));
        sql("ANALYZE;");
        measure("baseline");
        sql(indexes + "\nANALYZE;");
        measure("indexed");
        assertTrue(statements.size()>35,"Exercise the full query surface");
        System.out.println("Document performance: "+statements.size()+" native query shapes measured before and after the migration.");
    }
    private void measure(String stage) throws Exception {
        Path folder = Path.of("target","document-performance-"+System.getProperty("document.performance.label","current"),stage);
        Files.createDirectories(folder);
        List<String> summary = new ArrayList<>(List.of("query\toperation\tplanning_ms\texecution_ms\tindexes\tsequential_scans"));
        int index = 0;
        for (Statement statement : statements.values()) {
            String sql = statement.sql;
            boolean read = sql.startsWith("select") || sql.startsWith("with");
            if (statement.limit != Integer.MAX_VALUE) {
                int lock = sql.indexOf(" for update");
                String pagination = " limit " + statement.limit + " offset " + statement.offset;
                sql = lock<0 ? sql + pagination : sql.substring(0,lock)+pagination+sql.substring(lock);
            }
            String explain = "explain ("+(read?"analyze,buffers,":"")+"format text) "+sql;
            List<Plan> plans = new ArrayList<>();
            for (int sample=0;sample<(read?5:1);sample++) plans.add(fixture.run(c -> {
                var query = c.getItem1().createNativeQuery(explain,String.class);
                statement.parameters.forEach(query::setParameter);
                return query.getResultList().map(lines -> {
                    String text = String.join("\n",lines);
                    return new Plan(text,time(text,"Planning"),time(text,"Execution"));
                });
            }));
            plans.sort(Comparator.comparingDouble(Plan::execution));
            Plan plan = plans.get(plans.size()/2);
            Set<String> indexes = names(plan.text(),"(?:Index(?: Only)? Scan(?: Backward)? using|Bitmap Index Scan on) (\\w+)"),
                    scans = names(plan.text(),"(?:Parallel )?Seq Scan on (\\w+)");
            String id = String.format(Locale.ROOT,"%03d",++index);
            Files.writeString(folder.resolve(id+".sql"),sql);
            Files.writeString(folder.resolve(id+".plan"),plan.text());
            summary.add(id+"\t"+statement.operation+"\t"+plan.planning()+"\t"+
                    plan.execution()+"\t"+String.join(",",indexes)+"\t"+String.join(",",scans));
        }
        Files.write(folder.resolve("queries.tsv"),summary);
        if (stage.equals("baseline"))
            Files.write(folder.resolve("operations.tsv"),operations.entrySet().stream().map(e->e.getKey()+"\t"+e.getValue()).toList());
        System.out.println("Document performance plans: "+folder.toAbsolutePath());
    }
    // Ordinary secured arrangement, outside the Document Master, for the attachment authorization probe.
    private UUID attachment;
    private UUID targetArrangement() {
        if (attachment == null) attachment = fixture.run(c -> {
            com.guicedee.activitymaster.fsdm.client.services.IArrangementsService<?> arrangements =
                    com.guicedee.client.IGuiceContext.get(com.guicedee.activitymaster.fsdm.client.services.IArrangementsService.class);
            return arrangements.create(c.getItem1(),"Document Bucket",UUID.randomUUID(),"DocumentBucketType","1",c.getItem3(),fixture.token).map(a->a.getId());
        });
        return attachment;
    }
    private static double time(String plan,String kind) {
        var match = java.util.regex.Pattern.compile(kind+" Time: ([0-9.]+) ms").matcher(plan);
        return match.find() ? Double.parseDouble(match.group(1)) : 0;
    }
    private static Set<String> names(String plan,String regex) {
        Set<String> names = new TreeSet<>(); var matches = java.util.regex.Pattern.compile(regex).matcher(plan);
        while (matches.find()) names.add(matches.group(1)); return names;
    }
    private static void sql(String sql) throws Exception {
        var db = PostgreSQLTestDBModule.DATABASE;
        db.copyFileToContainer(Transferable.of(sql.getBytes(StandardCharsets.UTF_8)),"/tmp/document-performance.sql");
        var result = db.execInContainer("psql","-v","ON_ERROR_STOP=1","-U",db.getUsername(),"-d",db.getDatabaseName(),"-f","/tmp/document-performance.sql");
        assertEquals(0,result.getExitCode(),result.getStderr());
    }
}
