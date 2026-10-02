package org.ruoyi.workflow.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.workflow.entity.WorkflowRuntime;
import org.ruoyi.workflow.mapper.WorkflowRunMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.WORKFLOW_PROCESS_STATUS_DOING;
import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.WORKFLOW_PROCESS_STATUS_FAIL;
import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.WORKFLOW_PROCESS_STATUS_REMARK_INTERRUPTED;
import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.WORKFLOW_PROCESS_STATUS_SUCCESS;

/**
 * 僵尸 DOING 处置（D1）+ 续跑取数入口契约测试，mock mapper 行形态对齐真库可产生形态
 * （见 docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md 三条硬规约：
 * NOT NULL 列全赋值、is_deleted 恒非 null、时间戳落值，无真库不可能的组合）。
 * <p>
 * 钉死语义：
 * ① failZombieDoingRuntimes 只处置 status=DOING 且 update_time 早于阈值且未删的实例 → FAIL，
 *    status_remark=「进程中断，可从断点续跑」（不新增 status 枚举值，语义靠 remark 区分）；
 * ② 新鲜 DOING / 非 DOING / 已删实例不动；
 * ③ 阈值 ZERO = 重启后立即处置全部 DOING；
 * ④ getByUuidForResume 按 uuid 取数且不做当前用户过滤（断点续跑运维入口无登录态）。
 */
@Tag("dev")
@DisplayName("R31 僵尸 DOING 处置 + 断点续跑取数（mock mapper，行形态对齐真库）")
class WorkflowRuntimeZombieDisposeTest {

    @BeforeAll
    static void initMpLambdaCache() {
        // LambdaQueryWrapper 按 lambda 解析列名需要 TableInfo 预热（真库运行期由 mapper 扫描注册）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WorkflowRuntime.class);
    }

    @Test
    void staleZombieDoingIsMarkedFailWithResumableRemark() {
        FakeRuntimeDb db = new FakeRuntimeDb();
        WorkflowRuntime stale = db.seed(1L, "rt-zombie", WORKFLOW_PROCESS_STATUS_DOING, LocalDateTime.now().minusHours(2), false, "旧备注");
        WorkflowRuntime fresh = db.seed(2L, "rt-fresh", WORKFLOW_PROCESS_STATUS_DOING, LocalDateTime.now().minusMinutes(1), false, null);
        WorkflowRuntime done = db.seed(3L, "rt-done", WORKFLOW_PROCESS_STATUS_SUCCESS, LocalDateTime.now().minusHours(2), false, null);
        WorkflowRuntime deleted = db.seed(4L, "rt-deleted", WORKFLOW_PROCESS_STATUS_DOING, LocalDateTime.now().minusHours(2), true, null);

        int disposed = service(db).failZombieDoingRuntimes(Duration.ofHours(1));

        assertEquals(1, disposed, "只处置超时的僵尸 DOING，新鲜 DOING/非 DOING/已删不动");
        assertEquals(WORKFLOW_PROCESS_STATUS_FAIL, stale.getStatus().intValue());
        assertEquals(WORKFLOW_PROCESS_STATUS_REMARK_INTERRUPTED, stale.getStatusRemark());
        assertEquals(WORKFLOW_PROCESS_STATUS_DOING, fresh.getStatus().intValue());
        assertNull(fresh.getStatusRemark(), "未处置实例的 status_remark 不被改写");
        assertEquals(WORKFLOW_PROCESS_STATUS_SUCCESS, done.getStatus().intValue());
        assertEquals(WORKFLOW_PROCESS_STATUS_DOING, deleted.getStatus().intValue(), "逻辑删除行不处置");
        assertTrue(Boolean.TRUE.equals(deleted.getIsDeleted()));
    }

    @Test
    void zeroTimeoutDisposesAllDoingAsProcessRestartImmediateCleanup() {
        FakeRuntimeDb db = new FakeRuntimeDb();
        WorkflowRuntime a = db.seed(1L, "rt-a", WORKFLOW_PROCESS_STATUS_DOING, LocalDateTime.now().minusHours(2), false, null);
        WorkflowRuntime b = db.seed(2L, "rt-b", WORKFLOW_PROCESS_STATUS_DOING, LocalDateTime.now().minusMinutes(1), false, null);

        int disposed = service(db).failZombieDoingRuntimes(Duration.ZERO);

        assertEquals(2, disposed, "进程重启后立即处置：阈值 ZERO 收编全部 DOING");
        assertEquals(WORKFLOW_PROCESS_STATUS_REMARK_INTERRUPTED, a.getStatusRemark());
        assertEquals(WORKFLOW_PROCESS_STATUS_REMARK_INTERRUPTED, b.getStatusRemark());
    }

    @Test
    void getByUuidForResumeReturnsRowWithoutUserFilter() {
        FakeRuntimeDb db = new FakeRuntimeDb();
        WorkflowRuntime row = db.seed(7L, "rt-resume", WORKFLOW_PROCESS_STATUS_FAIL, LocalDateTime.now().minusMinutes(30), false, WORKFLOW_PROCESS_STATUS_REMARK_INTERRUPTED);
        row.setUserId(999L);

        // 无 ThreadContext 登录态也能取（getByUuid 会因当前用户过滤 NPE，续跑入口专门绕开）
        WorkflowRuntime found = service(db).getByUuidForResume("rt-resume");
        assertNotNull(found);
        assertEquals(7L, found.getId().longValue());
        assertEquals(WORKFLOW_PROCESS_STATUS_FAIL, found.getStatus().intValue());
        assertEquals(WORKFLOW_PROCESS_STATUS_REMARK_INTERRUPTED, found.getStatusRemark());
    }

    @Test
    void busyOwnerCannotBeMarkedAsZombie() throws Exception {
        FakeRuntimeDb db = new FakeRuntimeDb();
        WorkflowRuntime row = db.seed(10L, "rt-busy-owner", WORKFLOW_PROCESS_STATUS_DOING,
            LocalDateTime.now().minusHours(2), false, null);
        WorkflowRuntimeService service = service(db);
        var owner = new org.ruoyi.workflow.workflow.checkpoint.JdbcCheckpointSaver(null);
        try (var lease = owner.acquireRun("rt-busy-owner")) {
            assertEquals(0, service.failZombieDoingRuntimes(Duration.ZERO));
            assertEquals(WORKFLOW_PROCESS_STATUS_DOING, row.getStatus().intValue());
            assertNull(row.getStatusRemark());
        }
    }

    private static WorkflowRuntimeService service(FakeRuntimeDb db) {
        WorkflowRuntimeService service = new WorkflowRuntimeService();
        ReflectionTestUtils.setField(service, "baseMapper", db.mapper());
        ReflectionTestUtils.setField(service, "jdbcCheckpointSaver",
            new org.ruoyi.workflow.workflow.checkpoint.JdbcCheckpointSaver(null));
        return service;
    }

    /**
     * 内存行存储 fake mapper（mock mapper 范式）：只模拟 t_workflow_runtime 真库可产生的行形态与
     * 本测试路径实际调用的 BaseMapper 方法（selectList / selectOne / selectById / updateById）。
     * wrapper 过滤按 sqlSegment 的「列 操作符 {占位符}」逐条件模拟（等值/大小比较），
     * updateById 模拟 updateStrategy=NOT_NULL：仅实体非 null 字段进 SET。
     */
    private static final class FakeRuntimeDb {

        private long seq = 0;
        private final List<WorkflowRuntime> rows = new ArrayList<>();

        WorkflowRuntime seed(long id, String uuid, int status, LocalDateTime updateTime, boolean deleted, String statusRemark) {
            WorkflowRuntime row = new WorkflowRuntime();
            row.setId(id);
            row.setUuid(uuid);
            row.setUserId(1L);
            row.setWorkflowId(100L);
            row.setStatus(status);
            row.setStatusRemark(statusRemark);
            row.setCreateTime(updateTime.minusMinutes(5));
            row.setUpdateTime(updateTime);
            row.setIsDeleted(deleted);
            rows.add(row);
            seq = Math.max(seq, id);
            return row;
        }

        @SuppressWarnings("unchecked")
        WorkflowRunMapper mapper() {
            return (WorkflowRunMapper) Proxy.newProxyInstance(
                    WorkflowRunMapper.class.getClassLoader(),
                    new Class<?>[]{WorkflowRunMapper.class},
                    this::dispatch);
        }

        private Object dispatch(Object proxy, java.lang.reflect.Method method, Object[] args) {
            switch (method.getName()) {
                case "selectList":
                    return selectList((Wrapper<WorkflowRuntime>) args[0]);
                case "selectOne":
                    return selectOne((Wrapper<WorkflowRuntime>) args[0]);
                case "selectById":
                    return selectById(args[0]);
                case "update": {
                    WorkflowRuntime update = (WorkflowRuntime) args[0];
                    List<Predicate2> conditions = parseConditions((Wrapper<WorkflowRuntime>) args[1]);
                    int count = 0;
                    for (WorkflowRuntime row : rows) {
                        if (conditions.stream().allMatch(cond -> cond.test(row))) {
                            row.setStatus(update.getStatus());
                            row.setStatusRemark(update.getStatusRemark());
                            row.setUpdateTime(LocalDateTime.now());
                            count++;
                        }
                    }
                    return count;
                }
                case "updateById":
                    return updateById((WorkflowRuntime) args[0]);
                case "toString":
                    return "FakeWorkflowRunMapper";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException("FakeWorkflowRunMapper 未实现: " + method.getName());
            }
        }

        private List<WorkflowRuntime> selectList(Wrapper<WorkflowRuntime> wrapper) {
            List<Predicate2> conditions = parseConditions(wrapper);
            return rows.stream()
                    .filter(row -> conditions.stream().allMatch(cond -> cond.test(row)))
                    .map(this::copyOf)
                    .collect(Collectors.toList());
        }

        private WorkflowRuntime selectOne(Wrapper<WorkflowRuntime> wrapper) {
            List<WorkflowRuntime> list = selectList(wrapper);
            return list.isEmpty() ? null : list.get(0);
        }

        /** 模拟 @TableLogic：逻辑删除行查不到 */
        private WorkflowRuntime selectById(Object id) {
            return rows.stream()
                    .filter(row -> row.getId().equals(id) && !Boolean.TRUE.equals(row.getIsDeleted()))
                    .findFirst().map(this::copyOf).orElse(null);
        }

        /** 模拟 updateStrategy=NOT_NULL：仅实体非 null 字段进 SET，update_time 落值 */
        private int updateById(WorkflowRuntime entity) {
            for (WorkflowRuntime row : rows) {
                if (!row.getId().equals(entity.getId())) {
                    continue;
                }
                if (null != entity.getStatus()) {
                    row.setStatus(entity.getStatus());
                }
                if (null != entity.getStatusRemark()) {
                    row.setStatusRemark(entity.getStatusRemark());
                }
                row.setUpdateTime(LocalDateTime.now());
                return 1;
            }
            return 0;
        }

        private static final Pattern COND = Pattern.compile("`?([a-z_]+)`?\\s*(<=|>=|<>|=|<|>)\\s*#\\{[^}]*\\.(\\w+)\\}");

        /** 把 wrapper sqlSegment 的「列 操作符 {占位符}」翻成内存谓词（真库 WHERE 语义） */
        private static List<Predicate2> parseConditions(Wrapper<WorkflowRuntime> wrapper) {
            // MP 3.5.x 占位符参数惰性物化：先 getSqlSegment() 再取 paramNameValuePairs
            String sqlSegment = wrapper.getSqlSegment();
            Map<String, Object> params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
            Matcher matcher = COND.matcher(sqlSegment);
            List<Predicate2> conditions = new ArrayList<>();
            while (matcher.find()) {
                String column = matcher.group(1);
                String op = matcher.group(2);
                Object value = params.get(matcher.group(3));
                conditions.add(row -> matches(columnValue(row, column), op, value));
            }
            return conditions;
        }

        private static boolean matches(Object rowValue, String op, Object paramValue) {
            return switch (op) {
                case "=" -> java.util.Objects.equals(rowValue, paramValue);
                case "<>" -> !java.util.Objects.equals(rowValue, paramValue);
                case "<", "<=", ">", ">=" -> {
                    int cmp = ((Comparable<Object>) rowValue).compareTo(paramValue);
                    yield switch (op) {
                        case "<" -> cmp < 0;
                        case "<=" -> cmp <= 0;
                        case ">" -> cmp > 0;
                        default -> cmp >= 0;
                    };
                }
                default -> throw new UnsupportedOperationException("未支持操作符: " + op);
            };
        }

        private static Object columnValue(WorkflowRuntime row, String column) {
            return switch (column) {
                case "id" -> row.getId();
                case "uuid" -> row.getUuid();
                case "status" -> row.getStatus();
                case "status_remark" -> row.getStatusRemark();
                case "update_time" -> row.getUpdateTime();
                case "create_time" -> row.getCreateTime();
                case "is_deleted" -> row.getIsDeleted();
                case "user_id" -> row.getUserId();
                case "workflow_id" -> row.getWorkflowId();
                default -> throw new UnsupportedOperationException("未映射列: " + column);
            };
        }

        private WorkflowRuntime copyOf(WorkflowRuntime source) {
            WorkflowRuntime copy = new WorkflowRuntime();
            copy.setId(source.getId());
            copy.setUuid(source.getUuid());
            copy.setUserId(source.getUserId());
            copy.setWorkflowId(source.getWorkflowId());
            copy.setInput(source.getInput());
            copy.setOutput(source.getOutput());
            copy.setStatus(source.getStatus());
            copy.setStatusRemark(source.getStatusRemark());
            copy.setCreateTime(source.getCreateTime());
            copy.setUpdateTime(source.getUpdateTime());
            copy.setIsDeleted(source.getIsDeleted());
            return copy;
        }
    }

    @FunctionalInterface
    private interface Predicate2 {
        boolean test(WorkflowRuntime row);
    }
}
