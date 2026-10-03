package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.skill.AgentSkill;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.LoadedSkill;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 冻结技能快照的<b>篡改拒绝路径</b>反例（对应 AgentScope 审计 A20）。
 *
 * <p>既有 {@code FrozenProjectAgentSkillsTest} 把三种非法快照塞进一个循环，只断言
 * 「抛 {@code IllegalStateException}」。那条测试<b>分辨不出是哪道闸拦下的</b>——
 * 把哈希校验整段删掉、只留版本校验，它照样绿。本类逐个钉住每道闸的<b>专属异常文案</b>，
 * 并补上既有测试完全没覆盖的两类输入：审核通过的整包（bundle 分支）与身份格式非法
 * （{@code IllegalArgumentException} 分支，含格式错、版本空、重名）。
 *
 * <p>冻结快照是「本轮能执行哪个技能」的唯一授权来源（{@code AgentScopeProjectAgentKernel}
 * 用它构造 {@code SkillFilter}），因此「哈希不符仍加载成功」等于让未经审核的正文进入模型提示。
 */
@Tag("dev")
@DisplayName("冻结技能快照反例：SHA/版本/正文/整包被篡改一律拒绝")
class FrozenProjectAgentSkillsRejectionTest {

    /** 取一份真实 classpath 快照作为「未被篡改」的基线。 */
    private static LoadedSkill pristine() {
        return AgentTestFixtures.skillCatalog(AgentTestFixtures.manifest())
            .load("competitor-analysis-ipd").orElseThrow();
    }

    /** 与 {@code pristine} 同名、正文可解析的合法整包（用于 bundle 分支）。 */
    private static ProjectAgentSkillBundle bundleFor(String name, String body) {
        String markdown = "---\nname: " + name + "\ndescription: frozen fixture\n---\n" + body;
        var file = new ProjectAgentSkillBundle.File("SKILL.md", markdown,
            ProjectAgentSkillCatalog.sha256Hex(markdown), "utf-8");
        return new ProjectAgentSkillBundle(name, ProjectAgentSkillBundle.digest(List.of(file)), List.of(file));
    }

    /**
     * 整包对应「加载后应当得到的正文」——与生产代码同一算法（{@code SkillUtil.createFrom}），
     * 因为 freezer 拿它和整包解析结果做等值比对（{@code loaded.content()} 是
     * {@code AgentSkill.getSkillContent()}，不是 SKILL.md 原始字节）。
     */
    private static String bundleContent(ProjectAgentSkillBundle bundle) {
        return io.agentscope.core.skill.util.SkillUtil
            .createFrom(bundle.markdown(), bundle.textResources()).getSkillContent();
    }

    // ------------------------------------------------------------------
    // 对照组：未被篡改的快照必须能加载——否则下面的拒绝断言可能只是「一切都失败」
    // ------------------------------------------------------------------

    @Test
    @DisplayName("对照：未篡改的快照正常加载并只暴露自己被选中的那一个")
    void pristineSnapshotLoads() {
        LoadedSkill skill = pristine();
        FrozenProjectAgentSkills repository = new FrozenProjectAgentSkills(List.of(skill));

        assertThat(repository.getAllSkillNames()).containsExactly(skill.name());
        assertThat(repository.getSkill(skill.name())).isNotNull();
        assertThat(repository.getSkill(skill.name()).getName()).isEqualTo(skill.name());
    }

    // ------------------------------------------------------------------
    // 哈希分支：字节 SHA 不符
    // ------------------------------------------------------------------

    @Test
    @DisplayName("SHA 不匹配时必须由「哈希」那道闸拦下，而不是笼统的加载失败")
    void tamperedHashIsRejectedByTheHashGate() {
        LoadedSkill skill = pristine();
        LoadedSkill tampered = new LoadedSkill(skill.name(), skill.version(), "0".repeat(64), skill.content());

        assertThatThrownBy(() -> new FrozenProjectAgentSkills(List.of(tampered)))
            .as("克隆仓快照时最常见的攻击形态：正文没动，摘要被换")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Selected skill hash changed");
    }

    @Test
    @DisplayName("版本或正文被换（哈希仍然对得上）由另一道闸拦下，文案必须区分开")
    void versionOrBodyDriftIsRejectedByADistinctGate() {
        LoadedSkill skill = pristine();

        assertThatThrownBy(() -> new FrozenProjectAgentSkills(List.of(
            new LoadedSkill(skill.name(), "v-does-not-exist", skill.sha256(), skill.content()))))
            .as("哈希对了才轮到版本：文案必须与哈希那道不同，否则无法定位是哪一层被绕过")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Selected skill version or body changed");

        assertThatThrownBy(() -> new FrozenProjectAgentSkills(List.of(
            new LoadedSkill(skill.name(), skill.version(), skill.sha256(), skill.content() + "\n注入的一行"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Selected skill version or body changed");
    }

    @Test
    @DisplayName("SHA 格式非法在读取任何资源之前就被拒——不是等到比对失败")
    void malformedHashIsRejectedAsInvalidIdentity() {
        LoadedSkill skill = pristine();

        assertThatThrownBy(() -> new FrozenProjectAgentSkills(List.of(
            new LoadedSkill(skill.name(), skill.version(), "not-a-sha", skill.content()))))
            .as("格式非法的摘要不该走到比对逻辑")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid frozen skill identity");

        assertThatThrownBy(() -> new FrozenProjectAgentSkills(List.of(
            new LoadedSkill(skill.name(), skill.version(), "abc123", skill.content()))))
            .as("长度不足 64 位同样非法")
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // 身份分支：名称 / 版本 / 重复
    // ------------------------------------------------------------------

    @Test
    @DisplayName("技能名越出白名单字符集即拒绝——含路径分隔符的名字不得进入快照")
    void illegalSkillNameIsRejected() {
        LoadedSkill skill = pristine();

        for (String illegal : List.of("../escape", "a/b", "a b", "")) {
            assertThatThrownBy(() -> new FrozenProjectAgentSkills(List.of(
                new LoadedSkill(illegal, skill.version(), skill.sha256(), skill.content()))))
                .as("非法技能名 %s 必须被拒", illegal)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid frozen skill identity");
        }
    }

    @Test
    @DisplayName("版本号空白即拒绝")
    void blankVersionIsRejected() {
        LoadedSkill skill = pristine();

        for (String blank : List.of("", "   ")) {
            assertThatThrownBy(() -> new FrozenProjectAgentSkills(List.of(
                new LoadedSkill(skill.name(), blank, skill.sha256(), skill.content()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid frozen skill identity");
        }
    }

    @Test
    @DisplayName("同名两次出现即拒绝——后者不得静默覆盖前者")
    void duplicateSkillNameIsRejectedRatherThanOverwritten() {
        LoadedSkill skill = pristine();
        LoadedSkill impostor = new LoadedSkill(skill.name(), skill.version(), skill.sha256(),
            skill.content() + "\n被换掉的正文");

        assertThatThrownBy(() -> new FrozenProjectAgentSkills(List.of(skill, impostor)))
            .as("同名的第二条若不拒绝，就等于用「后者覆盖前者」绕过哈希校验")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid frozen skill identity");
    }

    @Test
    @DisplayName("选中的技能在 classpath 上不存在即拒绝，不得静默降级成空快照")
    void missingClasspathResourceIsRejected() {
        assertThatThrownBy(() -> new FrozenProjectAgentSkills(List.of(
            new LoadedSkill("no-such-skill-anywhere", "1.0.0", "a".repeat(64), "body"))))
            .as("静默降级会让「本轮授权执行某技能」变成一个没有该技能的空仓")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Selected skill resource is missing");
    }

    @Test
    @DisplayName("空/缺省选择是合法的空快照，但绝不等于放行任意技能")
    void emptySelectionIsClosedNotOpen() {
        assertThat(new FrozenProjectAgentSkills(List.of()).getAllSkillNames()).isEmpty();
        assertThat(new FrozenProjectAgentSkills(null).getAllSkillNames()).isEmpty();

        FrozenProjectAgentSkills repository = new FrozenProjectAgentSkills(List.of());
        assertThat(repository.skillExists("competitor-analysis-ipd")).isFalse();
        assertThat(repository.filter().isAllowed("competitor-analysis-ipd")).isFalse();
    }

    // ------------------------------------------------------------------
    // 审核整包分支（bundle）：既有测试零覆盖
    // ------------------------------------------------------------------

    @Test
    @DisplayName("整包摘要与冻结摘要不一致即拒绝——这是「用户审核过的那一份」的唯一绑定")
    void reviewedBundleHashMismatchIsRejected() {
        LoadedSkill skill = pristine();
        ProjectAgentSkillBundle bundle = bundleFor(skill.name(), "# 审核过的正文\n");
        // 冻结摘要刻意与整包摘要不同：模拟审核通过后字节被换、审核记录没换
        LoadedSkill tampered = new LoadedSkill(skill.name(), skill.version(), "0".repeat(64),
            bundleContent(bundle), bundle);

        assertThatThrownBy(() -> new FrozenProjectAgentSkills(List.of(tampered)))
            .as("冻结摘要 ≠ 整包摘要时必须拒绝，否则审核签的就是另一份正文")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Reviewed skill snapshot mismatch");
    }

    @Test
    @DisplayName("整包摘要对得上、但冻结正文与整包正文不同即拒绝")
    void reviewedBundleBodyMismatchIsRejected() {
        LoadedSkill skill = pristine();
        ProjectAgentSkillBundle bundle = bundleFor(skill.name(), "# 审核过的正文\n");
        LoadedSkill tampered = new LoadedSkill(skill.name(), skill.version(), bundle.sha256(),
            "# 另一份正文\n", bundle);

        assertThatThrownBy(() -> new FrozenProjectAgentSkills(List.of(tampered)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Reviewed skill body changed");
    }

    @Test
    @DisplayName("整包名与冻结名不一致即拒绝——不得把 B 技能的整包挂到 A 技能名下")
    void reviewedBundleNameMismatchIsRejected() {
        LoadedSkill skill = pristine();
        ProjectAgentSkillBundle foreign = bundleFor("another-skill-name", "# 别的技能\n");
        LoadedSkill tampered = new LoadedSkill(skill.name(), skill.version(), foreign.sha256(),
            bundleContent(foreign), foreign);

        assertThatThrownBy(() -> new FrozenProjectAgentSkills(List.of(tampered)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Reviewed skill snapshot mismatch");
    }

    @Test
    @DisplayName("整包正文格式非法（缺 frontmatter）即拒绝，不得当作空技能放行")
    void malformedBundleBodyIsRejected() {
        LoadedSkill skill = pristine();
        String markdown = "# 没有 frontmatter\n";
        var file = new ProjectAgentSkillBundle.File("SKILL.md", markdown,
            ProjectAgentSkillCatalog.sha256Hex(markdown), "utf-8");
        ProjectAgentSkillBundle bundle = new ProjectAgentSkillBundle(skill.name(),
            ProjectAgentSkillBundle.digest(List.of(file)), List.of(file));
        LoadedSkill tampered = new LoadedSkill(skill.name(), skill.version(), bundle.sha256(),
            markdown, bundle);

        assertThatThrownBy(() -> new FrozenProjectAgentSkills(List.of(tampered)))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("通过审核整包加载的技能，其元数据版本必须回填为冻结版本")
    void reviewedBundleKeepsFrozenVersionMetadata() {
        LoadedSkill skill = pristine();
        ProjectAgentSkillBundle bundle = bundleFor(skill.name(), "# 审核过的正文\n");
        LoadedSkill frozen = new LoadedSkill(skill.name(), skill.version(), bundle.sha256(),
            bundleContent(bundle), bundle);

        FrozenProjectAgentSkills repository = new FrozenProjectAgentSkills(List.of(frozen));
        AgentSkill loaded = repository.getSkill(skill.name());

        assertThat(loaded).isNotNull();
        assertThat(loaded.getName()).isEqualTo(skill.name());
        assertThat(loaded.getMetadataValue("version"))
            .as("整包加载的正文本身不带版本元数据，必须由 freezer 回填冻结版本，否则过滤器比对会落空")
            .isEqualTo(skill.version());
    }
}
