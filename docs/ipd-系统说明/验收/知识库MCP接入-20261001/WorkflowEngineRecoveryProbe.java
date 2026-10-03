import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.common.core.service.ConfigService;
import org.ruoyi.workflow.entity.*;
import org.ruoyi.workflow.mapper.WorkflowCheckpointMapper;
import org.ruoyi.workflow.mapper.WorkflowRunMapper;
import org.ruoyi.workflow.mapper.WorkflowRuntimeNodeMapper;
import org.ruoyi.workflow.service.WorkflowRuntimeNodeService;
import org.ruoyi.workflow.service.WorkflowRuntimeService;
import org.ruoyi.workflow.workflow.WfState;
import org.ruoyi.workflow.workflow.WorkflowEngine;
import org.ruoyi.workflow.workflow.checkpoint.JdbcCheckpointSaver;
import org.ruoyi.workflow.workflow.checkpoint.WorkflowCheckpointConfig;
import org.ruoyi.workflow.workflow.checkpoint.WorkflowCheckpointState;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArraySet;

/** 生产 WorkflowEngine + 生产 runtime/checkpoint 仓储的本机跨 JVM 探针。定义保持停用，不发邮件。 */
public class WorkflowEngineRecoveryProbe {
    static final String MARK = "engine-recovery-probe";
    static MysqlDataSource ds;
    static SqlSession session;
    static String kind;
    static String phase;
    static String faultUuid;
    static long workflowId;
    static long runtimeId;
    static String runtimeUuid;
    static CountDownLatch hold = new CountDownLatch(1);
    static Map<Long, String> titleByNode = new HashMap<>();
    static Map<Long, String> titleByRuntimeNode = new ConcurrentHashMap<>();
    static Set<String> started = new CopyOnWriteArraySet<>();
    static String seenB = "";
    static WorkflowEngine engine;

    public static void main(String[] args) throws Exception {
        phase = args[0];
        kind = args[1];
        if (!List.of("fail", "resume", "verify").contains(phase) || !List.of("replay", "effect", "parallel").contains(kind))
            throw new IllegalArgumentException("phase/kind");
        ds = new MysqlDataSource();
        ds.setURL("jdbc:mysql://127.0.0.1:13306/ipd_dev?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=UTC");
        ds.setUser(System.getenv("IPD_PROBE_DB_USER"));
        ds.setPassword(System.getenv("IPD_PROBE_DB_PASSWORD"));
        openSession();
        springTemplates();
        if (phase.equals("fail")) createFixture();
        else loadFixture(args[2]);
        if (phase.equals("verify")) {
            assertParallelFence();
            report();
            return;
        }
        User user = new User();
        user.setId(0L);
        Workflow workflow = workflow(true);
        List<WorkflowComponent> components = components();
        List<WorkflowNode> nodes = nodes();
        List<WorkflowEdge> edges = edges();
        ProbeRuntimeService runtimes = new ProbeRuntimeService();
        ProbeNodeService nodeService = new ProbeNodeService();
        bind(runtimes, session.getMapper(WorkflowRunMapper.class));
        bind(nodeService, session.getMapper(WorkflowRuntimeNodeMapper.class));
        FaultSaver saver = new FaultSaver(session.getMapper(WorkflowCheckpointMapper.class), ds);
        engine = new WorkflowEngine(workflow, components, nodes, edges, runtimes, nodeService, saver);
        if (phase.equals("fail")) engine.run(user, List.of(), null, null, null, null);
        else {
            WorkflowRuntime runtime = runtimes.getByUuidForResume(runtimeUuid);
            check(runtime != null, "runtime missing");
            engine.resume(user, runtime, null, null, null, null);
        }
        runtimeUuid = engine.getWfState().getUuid();
        runtimeId = oneLong("SELECT id FROM t_workflow_runtime WHERE uuid=?", runtimeUuid);
        if (kind.equals("replay")) assertReplay();
        if (kind.equals("effect")) assertEffect();
        if (kind.equals("parallel")) assertParallelFence();
        check(oneLong("SELECT is_enable FROM t_workflow WHERE id=?", workflowId) == 0, "probe workflow became public");
        report();
    }

    static void assertReplay() throws Exception {
        List<String> a = uuids("A", 3);
        List<String> b = uuids("B", 3);
        check(a.size() == 1, "A repeated or missing");
        if (phase.equals("fail")) {
            check(b.size() == 1, "failed B output missing");
            var cp = latest();
            check(strings(cp.getState().get("completed")).equals(List.of(nodeUuid("A"))), "checkpoint stored uncommitted B");
            check(strings(cp.getState().get("inFlight")).equals(List.of(nodeUuid("B"))), "frontier lost B");
            return;
        }
        check(b.size() == 2 && !b.get(0).equals(b.get(1)), "retry did not keep both B attempts");
        check(b.get(1).equals(seenB), "C did not bind the new B attempt");
        check(uuids("C", 3).size() == 1, "C missing");
        check(text("C", "output").contains("C-sees-engine-A-B"), "C output mismatch");
        check(!text("B", "output", 0).isBlank() && text("B", "output", 0).contains("engine-A-B"), "old B evidence lost");
    }

    static void assertEffect() throws Exception {
        check(uuids("A", 3).size() == 1, "A missing");
        check(uuids("B", null).isEmpty(), "MailSend node was invoked");
        check(!started.contains("B"), "MailSend entered production runNode");
        var cp = latest();
        check(strings(cp.getState().get("inFlight")).contains(nodeUuid("B")), "side-effect frontier missing");
        if (phase.equals("resume")) {
            String remark = oneString("SELECT status_remark FROM t_workflow_runtime WHERE id=?", runtimeId);
            check(remark.contains("副作用"), "resume did not refuse unknown side effect: " + remark);
            check(oneLong("SELECT status FROM t_workflow_runtime WHERE id=?", runtimeId) == 4, "refused run not failed");
        }
    }

    static void assertParallelFence() {
        check(started.isEmpty() || !started.contains("B8"), "B8 started during checkpoint failure");
        check(!started.contains("B9"), "B9 started during checkpoint failure");
        check(uuids("B8", null).isEmpty() && uuids("B9", null).isEmpty(), "unstarted branch persisted a runtime node");
        check(uuids("B0", 3).size() == 1, "B0 output was not committed before checkpoint fault");
    }

    static void createFixture() throws Exception {
        String wf = ("eprobe" + UUID.randomUUID().toString().replace("-", "")).substring(0, 32);
        workflowId = insert("INSERT INTO t_workflow(uuid,title,remark,user_id,is_public,is_enable) VALUES(?,?,?,0,0,0)", wf, MARK, MARK + " " + kind);
        Map<String, Long> ids = componentIds();
        List<String> titles = kind.equals("parallel") ? parallelTitles() : List.of("A", "B", "C");
        Map<String, String> nodeUuids = new LinkedHashMap<>();
        for (String title : titles) {
            String uuid = UUID.randomUUID().toString().replace("-", "");
            nodeUuids.put(title, uuid);
            long component = title.equals("A") ? ids.get("Start") : (kind.equals("effect") && title.equals("B") ? ids.get("MailSend") : ids.get("End"));
            String config = title.equals("A") ? "{\"prologue\":\"engine-A\"}" : (title.equals("B") ? "{\"result\":\"{input}-B\"}" : (title.equals("C") ? "{\"result\":\"C-sees-{input}\"}" : "{\"result\":\"ok\"}"));
            if (kind.equals("effect") && title.equals("B")) config = "{}";
            insert("INSERT INTO t_workflow_node(uuid,workflow_id,workflow_component_id,title,remark,input_config,node_config) VALUES(?,?,?,?,?,?,?)", uuid, workflowId, component, title, MARK, "{\"user_inputs\":[],\"ref_inputs\":[]}", config);
        }
        faultUuid = nodeUuids.get(kind.equals("parallel") ? "B0" : "B");
        List<String[]> links = kind.equals("parallel")
                ? titles.stream().filter(title -> !title.equals("A")).map(title -> new String[]{"A", title}).toList()
                : List.of(new String[]{"A", "B"}, new String[]{"B", "C"});
        for (String[] link : links) {
            insert("INSERT INTO t_workflow_edge(uuid,workflow_id,source_node_uuid,source_handle,target_node_uuid) VALUES(?,?,?,?,?)", UUID.randomUUID().toString().replace("-", ""), workflowId, nodeUuids.get(link[0]), "", nodeUuids.get(link[1]));
        }
    }

    static void loadFixture(String uuid) throws Exception {
        runtimeUuid = uuid;
        try (var c = ds.getConnection(); var q = c.prepareStatement("SELECT id,workflow_id FROM t_workflow_runtime WHERE uuid=? AND is_deleted=0")) {
            q.setString(1, uuid);
            try (var r = q.executeQuery()) {
                check(r.next(), "runtime not found");
                runtimeId = r.getLong(1);
                workflowId = r.getLong(2);
            }
        }
        faultUuid = nodeUuid(kind.equals("parallel") ? "B0" : "B");
    }

    static List<String> parallelTitles() {
        List<String> titles = new ArrayList<>();
        titles.add("A");
        for (int i = 0; i < 10; i++) titles.add("B" + i);
        return titles;
    }

    static void openSession() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("engine-probe", new JdbcTransactionFactory(), ds));
        for (Class<?> entity : List.of(WorkflowCheckpoint.class, WorkflowRuntime.class, WorkflowRuntimeNode.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), entity);
        configuration.addMapper(WorkflowCheckpointMapper.class);
        configuration.addMapper(WorkflowRunMapper.class);
        configuration.addMapper(WorkflowRuntimeNodeMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(true);
    }

    static void springTemplates() {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(ConfigService.class, () -> new ConfigService() {
            public String getConfigValue(String configKey) { return null; }
            public String getConfigValue(String category, String configKey) { return null; }
        });
        ctx.registerBean(org.ruoyi.workflow.util.SpringUtil.class);
        ctx.refresh();
    }

    static void bind(Object service, Object mapper) throws Exception {
        Class<?> type = service.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("baseMapper");
                field.setAccessible(true);
                field.set(service, mapper);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException("baseMapper");
    }

    static Workflow workflow(boolean memoryEnabled) throws Exception {
        Workflow workflow = new Workflow();
        workflow.setId(workflowId);
        workflow.setUuid(oneString("SELECT uuid FROM t_workflow WHERE id=?", workflowId));
        workflow.setIsPublic(false);
        workflow.setIsEnable(memoryEnabled);
        return workflow;
    }

    static List<WorkflowComponent> components() throws Exception {
        List<WorkflowComponent> result = new ArrayList<>();
        try (var c = ds.getConnection(); var q = c.prepareStatement("SELECT id,name FROM t_workflow_component WHERE name IN ('Start','End','MailSend')")) {
            try (var r = q.executeQuery()) { while (r.next()) { WorkflowComponent item = new WorkflowComponent(); item.setId(r.getLong(1)); item.setName(r.getString(2)); result.add(item); } }
        }
        check(result.size() == 3, "component catalog missing");
        return result;
    }

    static Map<String, Long> componentIds() throws Exception {
        Map<String, Long> ids = new HashMap<>();
        for (WorkflowComponent item : components()) ids.put(item.getName(), item.getId());
        return ids;
    }

    static List<WorkflowNode> nodes() throws Exception {
        List<WorkflowNode> result = new ArrayList<>();
        titleByNode.clear();
        try (var c = ds.getConnection(); var q = c.prepareStatement("SELECT id,uuid,workflow_component_id,title,input_config,node_config FROM t_workflow_node WHERE workflow_id=? AND remark=? AND is_deleted=0 ORDER BY id")) {
            q.setLong(1, workflowId); q.setString(2, MARK);
            try (var r = q.executeQuery()) { while (r.next()) {
                WorkflowNode node = new WorkflowNode();
                node.setId(r.getLong(1)); node.setUuid(r.getString(2)); node.setWorkflowId(workflowId);
                node.setWorkflowComponentId(r.getLong(3)); node.setTitle(r.getString(4));
                node.setInputConfig(r.getString(5)); node.setNodeConfig(r.getString(6));
                titleByNode.put(node.getId(), node.getTitle());
                result.add(node);
            }}
        }
        return result;
    }

    static List<WorkflowEdge> edges() throws Exception {
        List<WorkflowEdge> result = new ArrayList<>();
        try (var c = ds.getConnection(); var q = c.prepareStatement("SELECT source_node_uuid,source_handle,target_node_uuid FROM t_workflow_edge WHERE workflow_id=? AND is_deleted=0 ORDER BY id")) {
            q.setLong(1, workflowId);
            try (var r = q.executeQuery()) { while (r.next()) { WorkflowEdge edge = new WorkflowEdge(); edge.setWorkflowId(workflowId); edge.setSourceNodeUuid(r.getString(1)); edge.setSourceHandle(r.getString(2)); edge.setTargetNodeUuid(r.getString(3)); result.add(edge); } }
        }
        return result;
    }

    static final class ProbeRuntimeService extends WorkflowRuntimeService { }
    static final class ProbeNodeService extends WorkflowRuntimeNodeService {
        @Override public org.ruoyi.workflow.dto.workflow.WfRuntimeNodeDto createByState(User user, long wfNodeId, long wfRuntimeId, org.ruoyi.workflow.workflow.WfNodeState state) {
            String title = titleByNode.get(wfNodeId);
            started.add(title);
            if ("parallel".equals(kind) && "fail".equals(phase) && title != null && title.matches("B[1-7]")) {
                try { if (!hold.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("B0 checkpoint fault missing"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }
            var dto = super.createByState(user, wfNodeId, wfRuntimeId, state);
            titleByRuntimeNode.put(dto.getId(), title);
            return dto;
        }
        @Override public void updateInput(Long id, org.ruoyi.workflow.workflow.WfNodeState state) {
            if ("C".equals(titleByRuntimeNode.get(id)) && engine != null && engine.getWfState() != null)
                seenB = engine.getWfState().getNodeStateByNodeUuid(nodeUuid("B")).map(org.ruoyi.workflow.workflow.WfNodeState::getUuid).orElse("");
            super.updateInput(id, state);
        }
    }

    static final class FaultSaver extends JdbcCheckpointSaver {
        FaultSaver(WorkflowCheckpointMapper mapper, javax.sql.DataSource dataSource) { super(mapper, dataSource); }
        @Override public synchronized void put(WorkflowCheckpointConfig config, WorkflowCheckpointState checkpoint) throws Exception {
            List<String> completed = strings(checkpoint.getState().get("completed"));
            List<String> inFlight = strings(checkpoint.getState().get("inFlight"));
            if ("effect".equals(kind) && "fail".equals(phase) && inFlight.contains(faultUuid) && !completed.contains(faultUuid)) {
                super.put(config, checkpoint);
                throw new SQLException("PROBE halt before unknown side effect");
            }
            if ("fail".equals(phase) && !"effect".equals(kind) && completed.contains(faultUuid)) {
                if ("parallel".equals(kind)) hold.countDown();
                throw new SQLException("PROBE checkpoint fault after output commit");
            }
            super.put(config, checkpoint);
        }
    }

    static WorkflowCheckpointState latest() throws Exception {
        return new JdbcCheckpointSaver(session.getMapper(WorkflowCheckpointMapper.class), ds).get(WorkflowCheckpointConfig.builder().threadId(runtimeUuid).build()).orElseThrow();
    }
    static List<String> strings(Object raw) {
        if (!(raw instanceof Collection<?> values)) return List.of();
        return values.stream().map(String::valueOf).toList();
    }
    static String nodeUuid(String title) {
        try { return oneString("SELECT uuid FROM t_workflow_node WHERE workflow_id=? AND title=? AND remark=? AND is_deleted=0", workflowId, title, MARK); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    static List<String> uuids(String title, Integer status) {
        List<String> result = new ArrayList<>();
        String sql = "SELECT n.uuid FROM t_workflow_runtime_node n JOIN t_workflow_node d ON d.id=n.node_id WHERE n.workflow_runtime_id=? AND d.title=? AND n.is_deleted=0" + (status == null ? "" : " AND n.status=?") + " ORDER BY n.id";
        try (var c = ds.getConnection(); var q = c.prepareStatement(sql)) {
            q.setLong(1, runtimeId); q.setString(2, title); if (status != null) q.setInt(3, status);
            try (var r = q.executeQuery()) { while (r.next()) result.add(r.getString(1)); }
        } catch (SQLException error) { throw new IllegalStateException(error); }
        return result;
    }
    static String text(String title, String column) { return text(title, column, phase.equals("resume") && title.equals("B") ? 1 : 0); }
    static String text(String title, String column, int index) {
        List<String> values = new ArrayList<>();
        try (var c = ds.getConnection(); var q = c.prepareStatement("SELECT n." + column + " FROM t_workflow_runtime_node n JOIN t_workflow_node d ON d.id=n.node_id WHERE n.workflow_runtime_id=? AND d.title=? AND n.is_deleted=0 ORDER BY n.id")) {
            q.setLong(1, runtimeId); q.setString(2, title);
            try (var r = q.executeQuery()) { while (r.next()) values.add(Objects.toString(r.getString(1), "")); }
        } catch (SQLException error) { throw new IllegalStateException(error); }
        return values.get(index);
    }
    static long insert(String sql, Object... values) throws SQLException {
        try (var c = ds.getConnection(); var q = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(q, values); q.executeUpdate();
            try (var r = q.getGeneratedKeys()) { return r.next() ? r.getLong(1) : 0; }
        }
    }
    static long oneLong(String sql, Object... values) throws SQLException {
        try (var c = ds.getConnection(); var q = c.prepareStatement(sql)) { bind(q, values); try (var r = q.executeQuery()) { check(r.next(), sql); return r.getLong(1); } }
    }
    static String oneString(String sql, Object... values) throws SQLException {
        try (var c = ds.getConnection(); var q = c.prepareStatement(sql)) { bind(q, values); try (var r = q.executeQuery()) { check(r.next(), sql); return r.getString(1); } }
    }
    static void bind(PreparedStatement q, Object... values) throws SQLException { for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i]); }
    static void check(boolean ok, String reason) { if (!ok) throw new IllegalStateException(reason); }
    static void report() {
        System.out.println("PROBE_RESULT phase=" + phase + " kind=" + kind + " workflowId=" + workflowId + " runtimeId=" + runtimeId + " runtimeUuid=" + runtimeUuid + " started=" + started + " seenB=" + seenB);
    }
}
