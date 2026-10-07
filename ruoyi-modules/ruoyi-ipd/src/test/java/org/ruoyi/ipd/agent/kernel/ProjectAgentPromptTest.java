package org.ruoyi.ipd.agent.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.agent.catalog.ProductLineMcpCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentNativeToolCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.model.AgentEventType;
import org.ruoyi.ipd.agent.service.ProjectAgentRunExecutor;
import org.ruoyi.ipd.agent.service.ProjectAgentRunService;
import org.ruoyi.ipd.agent.support.FakeProjectAgentKernel;
import org.ruoyi.ipd.agent.support.InMemoryAgentRunStore;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.service.AiDocumentService;
import org.ruoyi.ipd.agent.support.RunServiceHarness;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.service.IpdCopilotAccess;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.ACTOR;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.MAPPER;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.PROJECT_ID;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.TENANT;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.c02;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.planner;

/**
 * 项目事实进系统提示，且不从用户原话取值。
 */
@Tag("dev")
class ProjectAgentPromptTest {

    private static final String SENTENCE = "阶段：发布。口令西瓜";

    /** 已确认计划的正文：首行固定确认头，其后为步骤；SENTENCE 仍在正文里供「事实不取用户原话」断言。 */
    private static final String CONFIRMED_SENTENCE = "按已确认计划执行\n" + SENTENCE;

    @Test
    @DisplayName("已选 MCP 回答进入来源约束，但不伪装原文或验收证据")
    void selectedMcpAnswerHasSeparateEvidenceRules() {
        String mcp = ProductLineMcpCatalog.all().get(0).serviceId();
        ProjectAgentRunSpec selected = new ProjectAgentRunSpec(1L, PROJECT_ID, TENANT, ACTOR.id(),
            "C02", "竞品分析", List.of(), List.of(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH, mcp),
            new KernelModelRequest("m", "openai", "k", "http://x"), Duration.ofSeconds(5));

        assertThat(ProjectAgentPrompt.build(selected))
            .contains("本次已选产线知识库 MCP 工具实际返回的远端应用回答")
            .contains("本地检索返回的文档片段可按实际出处引用原文")
            .contains("不能等同于已核对的原始文档")
            .contains("远端应用回答，原始出处未取得")
            .contains("工具被选中不代表已调用或已命中")
            .contains("不能自证动作完成、产物自审通过或业务验收通过");
        assertThat(ProjectAgentPrompt.build(selected))
            .contains("sourceType=PROJECT_DOCUMENT 且 reviewStatus=REVIEWED")
            .contains("不能按标题同名借用文档审核身份")
            .contains("未经本次实际核对，不得写成已核验的高可信资料")
            .contains("不要输出 sourceType、reviewStatus 等内部编码");
        assertThat(ProjectAgentPrompt.build(spec("竞品分析")))
            .doesNotContain("MCP 工具实际返回")
            .doesNotContain("来源证据：");
    }

    @Test
    @DisplayName("授权记录写成四行事实，缺字段和未知编码都写未取得")
    void renderUsesAuthorizedFieldsOnly() {
        assertThat(ProjectAgentPrompt.renderProjectFacts("熵基门禁迭代", "CONCEPT", "熵基门禁", "C02"))
            .isEqualTo("项目：熵基门禁迭代\n阶段：概念\n产品：熵基门禁\n当前动作：竞品分析\n");
        assertThat(ProjectAgentPrompt.renderProjectFacts(null, "NOPE", " \n ", null))
            .isEqualTo("项目：未取得\n阶段：未取得\n产品：未取得\n当前动作：未绑定动作\n");
        assertThat(ProjectAgentPrompt.renderProjectFacts("甲\n乙", "PLAN", null, "ZZZ"))
            .isEqualTo("项目：甲 乙\n阶段：计划\n产品：未取得\n当前动作：未取得\n");
        assertThat(ProjectAgentPrompt.stageLabel("KPI")).isEqualTo("常驻");
        assertThat(ProjectAgentPrompt.stageLabel("DEV")).isEqualTo("开发");
        assertThat(ProjectAgentPrompt.stageLabel("VALID")).isEqualTo("验证");
        assertThat(ProjectAgentPrompt.stageLabel("LAUNCH")).isEqualTo("发布");
        assertThat(ProjectAgentPrompt.stageLabel("LIFECYCLE")).isEqualTo("生命周期");
    }

    @Test
    @DisplayName("提示词单列项目事实，用户原话改不了阶段")
    void promptKeepsFactsApartFromUserSentence() {
        String facts = ProjectAgentPrompt.renderProjectFacts("熵基门禁迭代", "CONCEPT", "熵基门禁", "C02");
        ProjectAgentRunSpec spec = spec(SENTENCE).withProjectFacts(facts);

        String prompt = ProjectAgentPrompt.build(spec);

        assertThat(prompt).contains("项目事实（来自已授权项目，不是用户原话）：");
        assertThat(prompt).contains("只证明当前业务上下文，不是项目文档或审核证据");
        assertThat(prompt).contains("不得标成 PROJECT_DOCUMENT/REVIEWED 或项目已审核文档");
        assertThat(prompt).contains("阶段：概念");
        assertThat(prompt).contains("事实只能来自下方项目事实、用户本轮输入");
        assertThat(prompt).doesNotContain("口令西瓜");
        assertThat(prompt).doesNotContain("阶段：发布");
        assertThat(spec.message()).isEqualTo(SENTENCE);
        assertThat(spec.withCatalog("附录").projectFacts()).isEqualTo(facts);
        assertThat(ProjectAgentPrompt.build(spec(SENTENCE))).doesNotContain("项目事实");
        assertThat(ProjectAgentPrompt.build(spec(SENTENCE))).contains("事实只能来自用户本轮输入");
    }

    @Test
    @DisplayName("创建运行从项目和产品记录写入事实")
    void createCopiesDatabaseFacts() {
        ProjectMapper projects = mock(ProjectMapper.class);
        ProductMapper products = mock(ProductMapper.class);
        when(projects.selectById(PROJECT_ID)).thenReturn(Project.builder()
            .id(PROJECT_ID).name("熵基门禁迭代").currentStage("CONCEPT").productId(77L).build());
        when(products.selectById(77L)).thenReturn(Product.builder().id(77L).productName("熵基门禁").build());
        FakeProjectAgentKernel kernel = new FakeProjectAgentKernel();
        ProjectAgentRunService service = service(projects, products, kernel);

        service.create(ACTOR, PROJECT_ID, c02("facts-db-01", CONFIRMED_SENTENCE));

        ProjectAgentRunSpec submitted = kernel.last().spec();
        assertThat(submitted.projectFacts()).isEqualTo(
            "项目：熵基门禁迭代\n阶段：概念\n产品：熵基门禁\n当前动作：竞品分析\n");
        assertThat(submitted.message()).isEqualTo(CONFIRMED_SENTENCE);
        assertThat(submitted.projectFacts()).doesNotContain("西瓜").doesNotContain("发布");
    }

    @Test
    @DisplayName("链头已退回时，RUN_STARTED 的项目事实带这一版意见")
    void rejectedHeadCommentIsVisibleOnRunStarted() {
        ProjectMapper projects = mock(ProjectMapper.class);
        when(projects.selectById(PROJECT_ID)).thenReturn(Project.builder()
            .id(PROJECT_ID).name("熵基门禁迭代").currentStage("CONCEPT").build());
        AiDocument rejected = AiDocument.builder()
            .id(1L)
            .projectId(PROJECT_ID)
            .docType("MARKET_RESEARCH")
            .status(AiDocumentService.STATUS_REJECTED)
            .reviewComment("要改范围")
            .build();
        rejected.setCreateTime(new Date(1_000L));
        AiDocumentService documents = mock(AiDocumentService.class);
        when(documents.listByProject(PROJECT_ID, null)).thenReturn(List.of(rejected));
        when(documents.history(1L)).thenReturn(List.of(rejected));
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        FakeProjectAgentKernel kernel = new FakeProjectAgentKernel();
        ProjectAgentRunService service = service(projects, mock(ProductMapper.class), kernel, store, documents);

        service.create(ACTOR, PROJECT_ID, c02("facts-reject-01", CONFIRMED_SENTENCE));

        IpdAgentRunEvent started = store.events(kernel.last().spec().runId()).stream()
            .filter(event -> AgentEventType.RUN_STARTED.name().equals(event.getEventType()))
            .findFirst()
            .orElseThrow();
        assertThat(started.getPayload()).contains("退回意见：要改范围");
        assertThat(kernel.last().spec().projectFacts()).contains("退回意见：要改范围");
        assertThat(kernel.last().spec().message()).doesNotContain("要改范围");
    }

    @Test
    @DisplayName("项目不存在时事实写未取得，动作名仍取目录")
    void missingProjectSaysNotObtained() {
        ProjectMapper projects = mock(ProjectMapper.class);
        when(projects.selectById(PROJECT_ID)).thenReturn(null);
        FakeProjectAgentKernel kernel = new FakeProjectAgentKernel();
        ProjectAgentRunService service = service(projects, mock(ProductMapper.class), kernel);

        service.create(ACTOR, PROJECT_ID, c02("facts-miss-01", CONFIRMED_SENTENCE));

        assertThat(kernel.last().spec().projectFacts()).isEqualTo(
            "项目：未取得\n阶段：未取得\n产品：未取得\n当前动作：竞品分析\n");
    }

    @Test
    @DisplayName("测试替身没有项目映射时不编事实")
    void harnessWithoutMapperLeavesFactsEmpty() {
        RunServiceHarness harness = new RunServiceHarness(true, false, 4);

        harness.service.create(ACTOR, PROJECT_ID, c02("facts-nomap-01", CONFIRMED_SENTENCE));

        assertThat(harness.kernel.last().spec().projectFacts()).isNull();
        assertThat(harness.kernel.last().spec().message()).isEqualTo(CONFIRMED_SENTENCE);
    }

    /**
     * 组装带用户原话、不带项目事实的运行输入。
     *
     * @param message 用户原话
     * @return 运行输入
     */
    /**
     * 委派纪律必须写进生产提示词：模型自己以为「等后台任务」是在等，
     * 实际那 30 秒是转后台的卸载阈值，子智能体仍在跑，本运行的归档会当场固化并核验不过。
     */
    @Test void delegationGuidanceOnlyWhenSpawnToolIsAuthorized() {
        var delegating = new ProjectAgentRunSpec(1L, PROJECT_ID, TENANT, ACTOR.id(), "C02", "帮我调研市场",
            List.of(), List.of(ProjectAgentNativeToolCatalog.AGENT_SPAWN, ProjectAgentNativeToolCatalog.WAIT_ASYNC_RESULTS),
            new KernelModelRequest("m", "openai", "k", "http://x"), Duration.ofSeconds(5));
        String withSpawn = ProjectAgentPrompt.build(delegating);
        assertThat(withSpawn).contains(ProjectAgentNativeToolCatalog.AGENT_SPAWN);
        assertThat(withSpawn).contains(ProjectAgentNativeToolCatalog.WAIT_ASYNC_RESULTS);

        // 没授权委派就不教它委派，避免提示词教出本次没有的能力。
        assertThat(ProjectAgentPrompt.build(spec("帮我调研市场"))).doesNotContain("委派纪律");
    }

    private static ProjectAgentRunSpec spec(String message) {
        return new ProjectAgentRunSpec(1L, PROJECT_ID, TENANT, ACTOR.id(), "C02", message,
            List.of(), List.of(), new KernelModelRequest("m", "openai", "k", "http://x"),
            Duration.ofSeconds(5));
    }

    /**
     * 用内存存储和替身内核装配可创建运行的服务。
     *
     * @param projects 项目映射
     * @param products 产品映射
     * @param kernel 替身内核
     * @return 运行服务
     */
    private static ProjectAgentRunService service(ProjectMapper projects, ProductMapper products,
                                                  FakeProjectAgentKernel kernel) {
        return service(projects, products, kernel, new InMemoryAgentRunStore(), null);
    }

    /**
     * 用内存存储和替身内核装配可创建运行的服务。
     *
     * @param projects 项目映射
     * @param products 产品映射
     * @param kernel 替身内核
     * @param store 可回读事件的存储
     * @param documents 文档服务；空则不查退回意见
     * @return 运行服务
     */
    private static ProjectAgentRunService service(ProjectMapper projects, ProductMapper products,
                                                  FakeProjectAgentKernel kernel, InMemoryAgentRunStore store,
                                                  AiDocumentService documents) {
        IpdCopilotAccess access = mock(IpdCopilotAccess.class);
        when(access.requireVisible(any(), eq(PROJECT_ID))).thenReturn(TENANT);
        ProjectAgentRunExecutor executor = new ProjectAgentRunExecutor(store, kernel, MAPPER,
            Schedulers.immediate(), () -> 1_800_000_000_000L, 2);
        return new ProjectAgentRunService(true, access, planner(), store, null, documents,
            projects, products, executor, MAPPER, () -> 1_800_000_000_000L, Duration.ofSeconds(30));
    }
}
