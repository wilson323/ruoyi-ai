package org.ruoyi.workflow.workflow.checkpoint;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.ruoyi.workflow.entity.WorkflowCheckpoint;
import org.ruoyi.workflow.workflow.data.NodeIOData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表驱动契约测试：{@link JdbcCheckpointSaver} 对 {@code AbstractCheckpointSaver} 四方法的栈序语义
 * （push 头插、peek 最新、按 id 替换保栈位、release 清理）与 thread_id 缺省落 $default 的行为钉死。
 * <p>
 * fake mapper 行形态与真库可产生形态一致（见 {@link FakeCheckpointDb}）；
 * 未覆盖 MyBatis-Plus wrapper SQL 的 MySQL 执行语义（由 DDL apply + SHOW CREATE TABLE 回读实证）。
 */
@Tag("dev")
@DisplayName("R31 JdbcCheckpointSaver 四方法栈序契约（mock mapper，行形态对齐真库）")
class JdbcCheckpointSaverContractTest {

    private static final String THREAD = "rt-uuid-0001";
    private static final String OTHER_THREAD = "rt-uuid-0002";

    @BeforeAll
    static void initMpLambdaCache() {
        // LambdaQueryWrapper 按 lambda 解析列名需要 TableInfo 预热（真库运行期由 mapper 扫描注册）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), WorkflowCheckpoint.class);
    }

    enum Contract {
        /** put=push 头插：list 最新在头，get=peek 取最新 */
        PUSH_PEEK_STACK_ORDER,
        /** get 带 checkPointId 精确取指定 checkpoint */
        GET_BY_CHECKPOINT_ID,
        /** put 带 checkPointId=按 id 就地替换：内容更新、栈位不变 */
        REPLACE_BY_ID_KEEPS_POSITION,
        /** release 清理该 thread：Tag 带出释放前快照，之后 list 空、行逻辑删除 */
        RELEASE_CLEARS_THREAD,
        /** thread_id 隔离：不同实例 uuid 的 checkpoint 互不可见 */
        THREAD_ISOLATION,
        /** 缺 thread_id 落 $default 桶（BaseCheckpointSaver.THREAD_ID_DEFAULT），不与显式 thread 串台 */
        DEFAULT_BUCKET_WHEN_THREAD_ID_MISSING
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Contract.class)
    void contract(Contract scenario) throws Exception {
        FakeCheckpointDb db = new FakeCheckpointDb();
        JdbcCheckpointSaver saver = new JdbcCheckpointSaver(db.mapper());
        RunnableConfig config = RunnableConfig.builder().threadId(THREAD).build();

        switch (scenario) {
            case PUSH_PEEK_STACK_ORDER -> {
                Checkpoint cp1 = checkpoint("n1", "n2", "state-1");
                Checkpoint cp2 = checkpoint("n2", "n3", "state-2");
                Checkpoint cp3 = checkpoint("n3", "n4", "state-3");
                saver.put(config, cp1);
                saver.put(config, cp2);
                saver.put(config, cp3);
                // 栈序：最新在头（基类 put=push 头插；load 按插入序还原后逐条 push）
                assertEquals(List.of(cp3.getId(), cp2.getId(), cp1.getId()), idList(saver.list(config)));
                // get=peek 最新
                assertEquals(cp3.getId(), saver.get(config).orElseThrow().getId());
                // 落库行数 = 栈深，行形态完整
                assertEquals(3, db.rows().size());
                for (WorkflowCheckpoint row : db.rows()) {
                    assertNotNull(row.getId());
                    assertEquals(THREAD, row.getThreadId());
                    assertNotNull(row.getCheckpointId());
                    assertNotNull(row.getNodeId());
                    assertNotNull(row.getNextNodeId());
                    assertNotNull(row.getStateJson());
                    assertNotNull(row.getCreateTime());
                    assertNotNull(row.getUpdateTime());
                    assertEquals(Boolean.FALSE, row.getIsDeleted());
                }
            }
            case GET_BY_CHECKPOINT_ID -> {
                Checkpoint cp1 = checkpoint("n1", "n2", "state-1");
                Checkpoint cp2 = checkpoint("n2", "n3", "state-2");
                saver.put(config, cp1);
                saver.put(config, cp2);
                RunnableConfig byId = RunnableConfig.builder()
                        .threadId(THREAD).checkPointId(cp1.getId()).build();
                assertEquals(cp1.getId(), saver.get(byId).orElseThrow().getId());
                assertEquals("state-1", saver.get(byId).orElseThrow().getState().get("payload"));
            }
            case REPLACE_BY_ID_KEEPS_POSITION -> {
                Checkpoint cp1 = checkpoint("n1", "n2", "state-1");
                Checkpoint cp2 = checkpoint("n2", "n3", "state-2");
                Checkpoint cp3 = checkpoint("n3", "n4", "state-3");
                saver.put(config, cp1);
                saver.put(config, cp2);
                saver.put(config, cp3);
                // 与 CompiledGraph.updateState 同型：带 checkPointId 的 put 触发 updatedCheckpoint
                Checkpoint cp2Updated = Checkpoint.builder().id(cp2.getId())
                        .nodeId("n2x").nextNodeId("n3x").state(Map.of("payload", "state-2-updated")).build();
                RunnableConfig replace = RunnableConfig.builder()
                        .threadId(THREAD).checkPointId(cp2.getId()).build();
                saver.put(replace, cp2Updated);
                // 栈位不变（仍在第 2 位）、内容已换；行数不增
                assertEquals(List.of(cp3.getId(), cp2Updated.getId(), cp1.getId()), idList(saver.list(config)));
                assertEquals(3, db.rows().size());
                assertEquals("state-2-updated", saver.get(replace).orElseThrow().getState().get("payload"));
            }
            case RELEASE_CLEARS_THREAD -> {
                Checkpoint cp1 = checkpoint("n1", "n2", "state-1");
                Checkpoint cp2 = checkpoint("n2", "n3", "state-2");
                saver.put(config, cp1);
                saver.put(config, cp2);
                BaseCheckpointSaver.Tag tag = saver.release(config);
                // Tag(threadId, 释放前快照)
                assertEquals(THREAD, tag.threadId());
                assertEquals(List.of(cp2.getId(), cp1.getId()),
                        tag.checkpoints().stream().map(Checkpoint::getId).toList());
                // 清理后：list 空 + 行逻辑删除（与仓库 softDelete 惯例一致）
                assertTrue(saver.list(config).isEmpty());
                assertTrue(saver.get(config).isEmpty());
                assertEquals(2, db.rows().size());
                assertTrue(db.rows().stream().allMatch(row -> Boolean.TRUE.equals(row.getIsDeleted())));
            }
            case THREAD_ISOLATION -> {
                Checkpoint mine = checkpoint("n1", "n2", "state-mine");
                Checkpoint other = checkpoint("n9", "n8", "state-other");
                saver.put(config, mine);
                saver.put(RunnableConfig.builder().threadId(OTHER_THREAD).build(), other);
                assertEquals(List.of(mine.getId()), idList(saver.list(config)));
                assertEquals(List.of(other.getId()),
                        idList(saver.list(RunnableConfig.builder().threadId(OTHER_THREAD).build())));
            }
            case DEFAULT_BUCKET_WHEN_THREAD_ID_MISSING -> {
                // 引擎漏传 thread_id 的行为钉死：落 $default 桶
                RunnableConfig noThread = RunnableConfig.builder().build();
                Checkpoint cp = checkpoint("n1", "n2", "state-default");
                saver.put(noThread, cp);
                assertEquals(BaseCheckpointSaver.THREAD_ID_DEFAULT, db.rows().get(0).getThreadId());
                assertEquals("$default", db.rows().get(0).getThreadId());
                assertEquals(List.of(cp.getId()), idList(saver.list(noThread)));
                // 不与显式 thread 串台
                assertTrue(saver.list(config).isEmpty());
            }
        }
    }

    private static Checkpoint checkpoint(String nodeId, String nextNodeId, String payload) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("payload", payload);
        state.put("input", NodeIOData.createByText("input", "用户输入", payload));
        return Checkpoint.builder().id(java.util.UUID.randomUUID().toString())
                .nodeId(nodeId).nextNodeId(nextNodeId).state(state).build();
    }

    private static List<String> idList(Collection<Checkpoint> checkpoints) {
        return checkpoints.stream().map(Checkpoint::getId).toList();
    }
}
