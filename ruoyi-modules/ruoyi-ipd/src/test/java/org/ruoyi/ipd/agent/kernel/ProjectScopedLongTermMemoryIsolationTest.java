package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.IpdAgentMemory;
import org.ruoyi.ipd.mapper.IpdAgentMemoryMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 个人长期记忆的<b>越界读取 / 撤权后读取</b>反例（对应 AgentScope 审计 A19）。
 *
 * <p>既有 {@code ProjectScopedLongTermMemoryTest} 只证明「召回参数被如实转发」
 * （{@code verify(mapper).recallForScope(PROJECT, PERSON, LIMIT)}）——那是接线测试，
 * 不回答「换个项目 / 换个人来读，拿得到吗」。本类补两件事：
 *
 * <ol>
 *   <li><b>行为面</b>：用按作用域过滤的替身 mapper 走完整 {@code record → retrieve} 链路，
 *       断言 A 的笔记在 B（同项目他人 / 他人项目同一人）手里读不到，且撤权（status 废弃）
 *       与逻辑删除后连本人也读不到。替身只复刻 {@code recallForScope} 的 SQL 语义，
 *       真实隔离条件仍由第 2 点钉住。</li>
 *   <li><b>执行点面</b>：隔离与撤权的<b>唯一执行点是 Mapper 上的手写 SQL</b>。
 *       Java 侧没有任何二次过滤（{@code retrieve} 直接信任 mapper 返回值），
 *       所以 SQL 里少一个 {@code person_id} 条件就是跨人泄漏、少一个
 *       {@code status IN ('0','1')} 就是废弃记忆复活。本类把这几条谓词读注解原文钉死，
 *       与既有 {@code versionTenantQueryRequiresUndeletedDocumentAndProjectWithSameTenant}
 *       同一手法。</li>
 * </ol>
 */
@Tag("dev")
@DisplayName("长期记忆反例：跨项目/跨人越界读取 + 撤权与删除后不得召回")
class ProjectScopedLongTermMemoryIsolationTest {

    private static final Long PROJECT_A = 1001L;
    private static final Long PROJECT_B = 2002L;
    private static final Long PERSON_A = 900101L;
    private static final Long PERSON_B = 900102L;

    // ------------------------------------------------------------------
    // 执行点面：隔离与撤权条件的唯一载体是 Mapper 手写 SQL
    // ------------------------------------------------------------------

    private static String sqlOf(String method, java.util.function.Function<Method, String> extractor)
        throws Exception {
        Method target = IpdAgentMemoryMapper.class.getMethod(method, Long.class, Long.class, Long.class);
        return extractor.apply(target).replaceAll("\\s+", " ");
    }

    private static String recallSql() throws Exception {
        Method target = IpdAgentMemoryMapper.class.getMethod("recallForScope", Long.class, Long.class, int.class);
        return String.join(" ", target.getAnnotation(Select.class).value()).replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("召回 SQL 必须同时钉 project_id + person_id——少任一条即跨用户泄漏")
    void recallSqlCarriesBothScopePredicates() throws Exception {
        String sql = recallSql();
        assertThat(sql)
            .as("两个作用域维度必须在同一条 AND 链上，缺 person_id 就是「同项目所有人都能读到」")
            .contains("project_id = #{projectId} AND person_id = #{personId}");
        assertThat(sql).contains("del_flag = '0'");
    }

    @Test
    @DisplayName("召回 SQL 只放行候选(0)与已晋升(1)——废弃态(2)不得出现在 IN 列表里")
    void recallSqlNeverAdmitsDiscardedStatus() throws Exception {
        String sql = recallSql();
        assertThat(sql)
            .as("撤权 = status 置 '2'；若 '2' 落入 IN 列表，撤掉的记忆会立刻复活")
            .contains("status IN ('0', '1')");
        assertThat(sql)
            .as("废弃态不得出现在任何可召回集合中")
            .doesNotContain("'0', '1', '2'");
    }

    @Test
    @DisplayName("晋升/废弃/硬删除 SQL 都带 project_id + person_id——不能对别人的记忆动手")
    void mutationSqlIsScopedToProjectAndPerson() throws Exception {
        for (String method : List.of("promoteTo", "discard")) {
            String sql = sqlOf(method, m -> String.join(" ", m.getAnnotation(Update.class).value()));
            assertThat(sql)
                .as("%s 缺 project_id 或 person_id 时，任何人可用裸 id 改别人的记忆状态", method)
                .contains("project_id = #{projectId} AND person_id = #{personId}");
        }
        String purge = sqlOf("purge", m -> String.join(" ", m.getAnnotation(Delete.class).value()));
        assertThat(purge).contains("project_id = #{projectId} AND person_id = #{personId}");
    }

    @Test
    @DisplayName("晋升 SQL 带 status='0' 前置条件——不得把已废弃记忆改回可召回态")
    void promoteSqlCannotResurrectDiscardedRow() throws Exception {
        String sql = sqlOf("promoteTo", m -> String.join(" ", m.getAnnotation(Update.class).value()));
        assertThat(sql)
            .as("缺 status = '0' 前置条件时，对废弃条调用晋升即可让它重新被召回")
            .contains("status = '0'");
    }

    @Test
    @DisplayName("废弃态在实体层即不可召回——isRecallable 不认 STATUS_DISCARDED")
    void discardedStatusIsNotRecallable() {
        assertThat(IpdAgentMemory.builder().status(IpdAgentMemory.STATUS_CANDIDATE).build().isRecallable())
            .isTrue();
        assertThat(IpdAgentMemory.builder().status(IpdAgentMemory.STATUS_PROMOTED).build().isRecallable())
            .isTrue();
        assertThat(IpdAgentMemory.builder().status(IpdAgentMemory.STATUS_DISCARDED).build().isRecallable())
            .isFalse();
    }

    // ------------------------------------------------------------------
    // 行为面：record → retrieve 全链路的作用域隔离与撤权
    // ------------------------------------------------------------------

    /** 复刻 recallForScope 的 SQL 语义（作用域 + 未删除 + 可召回态）的替身存储。 */
    private static final class ScopeAwareStore {
        private final List<IpdAgentMemory> rows = new ArrayList<>();

        IpdAgentMemoryMapper mapper() {
            IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
            when(mapper.insertIgnoreDuplicate(any())).thenAnswer(invocation -> {
                IpdAgentMemory row = invocation.getArgument(0);
                boolean duplicate = rows.stream()
                    .anyMatch(existing -> Objects.equals(existing.getProjectId(), row.getProjectId())
                        && Objects.equals(existing.getPersonId(), row.getPersonId())
                        && Objects.equals(existing.getSourceDigest(), row.getSourceDigest()));
                if (duplicate) {
                    return 0;
                }
                rows.add(row);
                return 1;
            });
            when(mapper.recallForScope(anyLong(), anyLong(), anyInt())).thenAnswer(invocation -> {
                Long projectId = invocation.getArgument(0);
                Long personId = invocation.getArgument(1);
                Integer limit = invocation.getArgument(2);
                return rows.stream()
                    .filter(row -> Objects.equals(row.getProjectId(), projectId)
                        && Objects.equals(row.getPersonId(), personId))
                    .filter(row -> "0".equals(row.getDelFlag()) && row.isRecallable())
                    .limit(limit)
                    .toList();
            });
            return mapper;
        }

        IpdAgentMemory only() {
            assertThat(rows).hasSize(1);
            return rows.get(0);
        }
    }

    private static Model modelReturning(String text) {
        Model model = mock(Model.class);
        when(model.stream(any(), any(), any())).thenReturn(
            reactor.core.publisher.Flux.just(ChatResponse.builder()
                .finishReason("stop")
                .content(List.of(TextBlock.builder().text(text).build()))
                .build()));
        return model;
    }

    private static Msg query(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static List<Msg> said(String text) {
        return List.of(query(text));
    }

    @Test
    @DisplayName("A 的记忆换人或换项目来读都拿不到——同项目他人 / 他人项目同一人两条越界路径")
    void memoryWrittenByOneScopeIsInvisibleToEveryOtherScope() {
        ScopeAwareStore store = new ScopeAwareStore();
        IpdAgentMemoryMapper mapper = store.mapper();

        new ProjectScopedLongTermMemory(PROJECT_A, PERSON_A, 1L, mapper,
            modelReturning("MEM|PREFERENCE|要表格，不要长段落"))
            .record(said("以后都给我表格")).block(Duration.ofSeconds(10));

        // 本人读得到——先证明这条链路本身工作，否则下面的 null 无法区分「隔离生效」与「根本没写进去」
        String ownRecall = new ProjectScopedLongTermMemory(PROJECT_A, PERSON_A, 2L, mapper, null)
            .retrieve(query("再来一份")).block(Duration.ofSeconds(10));
        assertThat(ownRecall).as("本人必须读得到自己的笔记").contains("要表格，不要长段落");

        // 越界路径 1：同项目、换个人
        String otherPerson = new ProjectScopedLongTermMemory(PROJECT_A, PERSON_B, 3L, mapper, null)
            .retrieve(query("再来一份")).block(Duration.ofSeconds(10));
        assertThat(otherPerson)
            .as("同项目他人不得读到本人的个人笔记")
            .isNull();

        // 越界路径 2：换项目、同一个人
        String otherProject = new ProjectScopedLongTermMemory(PROJECT_B, PERSON_A, 4L, mapper, null)
            .retrieve(query("再来一份")).block(Duration.ofSeconds(10));
        assertThat(otherProject)
            .as("同一人在另一个项目下不得读到本项目笔记")
            .isNull();
    }

    @Test
    @DisplayName("撤权（status 置废弃）后连本人也读不到")
    void discardedMemoryIsNoLongerRecalled() {
        ScopeAwareStore store = new ScopeAwareStore();
        IpdAgentMemoryMapper mapper = store.mapper();
        new ProjectScopedLongTermMemory(PROJECT_A, PERSON_A, 1L, mapper,
            modelReturning("MEM|FACT|已确认走方案 B"))
            .record(said("方案定了走 B")).block(Duration.ofSeconds(10));

        ProjectScopedLongTermMemory reader =
            new ProjectScopedLongTermMemory(PROJECT_A, PERSON_A, 2L, mapper, null);
        assertThat(reader.retrieve(query("方案定了吗")).block(Duration.ofSeconds(10)))
            .as("撤权前本人可召回")
            .contains("已确认走方案 B");

        store.only().setStatus(IpdAgentMemory.STATUS_DISCARDED);

        assertThat(reader.retrieve(query("方案定了吗")).block(Duration.ofSeconds(10)))
            .as("撤权后不得再被召回，否则撤权只是改了字段、没有改变行为")
            .isNull();
    }

    @Test
    @DisplayName("逻辑删除（del_flag 非 '0'）后不得被召回")
    void logicallyDeletedMemoryIsNoLongerRecalled() {
        ScopeAwareStore store = new ScopeAwareStore();
        IpdAgentMemoryMapper mapper = store.mapper();
        new ProjectScopedLongTermMemory(PROJECT_A, PERSON_A, 1L, mapper,
            modelReturning("MEM|OBSERVATION|关注成本"))
            .record(said("这次关注成本")).block(Duration.ofSeconds(10));

        ProjectScopedLongTermMemory reader =
            new ProjectScopedLongTermMemory(PROJECT_A, PERSON_A, 2L, mapper, null);
        assertThat(reader.retrieve(query("我关注什么")).block(Duration.ofSeconds(10))).contains("关注成本");

        store.only().setDelFlag("1");
        assertThat(reader.retrieve(query("我关注什么")).block(Duration.ofSeconds(10))).isNull();
    }

    @Test
    @DisplayName("无人可读的空作用域：B 读到的是空而非他人内容（宁可不注入也不越界）")
    void otherScopeGetsEmptyRatherThanForeignContent() {
        ScopeAwareStore store = new ScopeAwareStore();
        IpdAgentMemoryMapper mapper = store.mapper();
        new ProjectScopedLongTermMemory(PROJECT_A, PERSON_A, 1L, mapper,
            modelReturning("MEM|FACT|甲项目敏感结论"))
            .record(said("结论是甲")).block(Duration.ofSeconds(10));

        String foreign = new ProjectScopedLongTermMemory(PROJECT_B, PERSON_B, 2L, mapper, null)
            .retrieve(query("有什么结论")).block(Duration.ofSeconds(10));

        assertThat(foreign).as("越界读取必须返回 null（SDK 跳过注入），不得回退成空壳文本").isNull();
        assertThat(store.only().getPersonId()).as("原始记录的作用域未被越界调用改写").isEqualTo(PERSON_A);
        assertThat(store.only().getProjectId()).isEqualTo(PROJECT_A);
    }

    @Test
    @DisplayName("record 与 retrieve 复用同一作用域实例：写入的 projectId/personId 必须来自构造期绑定")
    void persistedRowCarriesTheBoundScope() {
        ScopeAwareStore store = new ScopeAwareStore();
        new ProjectScopedLongTermMemory(PROJECT_A, PERSON_A, 7L, store.mapper(),
            modelReturning("MEM|PREFERENCE|要表格"))
            .record(said("要表格")).block(Duration.ofSeconds(10));

        IpdAgentMemory row = store.only();
        assertThat(row.getProjectId()).isEqualTo(PROJECT_A);
        assertThat(row.getPersonId()).isEqualTo(PERSON_A);
        assertThat(row.getStatus()).isEqualTo(IpdAgentMemory.STATUS_CANDIDATE);
        assertThat(row.getDelFlag()).isEqualTo("0");
    }
}
