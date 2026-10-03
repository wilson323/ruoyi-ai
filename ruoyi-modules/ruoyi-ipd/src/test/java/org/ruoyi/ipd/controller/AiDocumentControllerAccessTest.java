package org.ruoyi.ipd.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.dto.AiGenerateReq;
import org.ruoyi.ipd.mapper.AiDocumentMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.AiDocumentService;
import org.ruoyi.ipd.service.AiGenerationService;
import org.ruoyi.ipd.service.IpdCopilotAccess;
import org.ruoyi.ipd.service.ProjectService;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HTTP 文档入口必须按真实 Person 的项目成员关系授权，并校验路径版本链。 */
@Tag("dev")
class AiDocumentControllerAccessTest {

    @BeforeAll
    static void initAiDocumentLambdaMetadata() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "ai-document-access-red"),
            AiDocument.class);
    }

    private static final IpdActor ACTOR = new IpdActor(9001L, "alice", "MARKET_PM", 100L);

    private final AiDocumentMapper mapper = mock(AiDocumentMapper.class);
    private final AiDocumentService documents = new AiDocumentService(mapper);
    private final AiGenerationService generation = mock(AiGenerationService.class);
    private final IpdPermission permission = mock(IpdPermission.class);
    private final IpdCopilotAccess access = mock(IpdCopilotAccess.class);
    private final ProjectService projectRead = mock(ProjectService.class);
    private final AiDocumentController controller = new AiDocumentController(documents, generation, permission);

    AiDocumentControllerAccessTest() {
        when(permission.requireInternal()).thenReturn(ACTOR);
        documents.setProjectReadAccess(projectRead);
        // 红测先于生产改动：当前 service 没有对象授权依赖；新增字段后同一用例自动注入拒绝桩。
        for (Field field : AiDocumentService.class.getDeclaredFields()) {
            if (field.getType() == IpdCopilotAccess.class) {
                try {
                    field.setAccessible(true);
                    field.set(documents, access);
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
    }

    @Test
    void listRejectsProjectOutsideActiveMembership() {
        doThrow(new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见"))
            .when(projectRead).getVisibleById(77L, ACTOR);

        assertThatThrownBy(() -> controller.listByProject(77L))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND));
        verify(mapper, never()).selectList(any());
    }

    @Test
    void versionsRejectsDocumentFromInvisibleProject() {
        when(mapper.selectChain(10L)).thenReturn(List.of(document(10L, 100L, null, 1, "REVIEWED")));
        doThrow(new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见"))
            .when(projectRead).getVisibleById(100L, ACTOR);

        assertThatThrownBy(() -> controller.versions(10L))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND));
    }

    @Test
    void createRejectsInvisibleProjectBeforeInsert() {
        doThrow(new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见"))
            .when(access).requireVisible(ACTOR, 77L);

        assertThatThrownBy(() -> controller.create(new AiDocumentController.CreateReq(
            77L, "PRD", "标题", "正文", "model", 10, 20)))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND));
        verify(mapper, never()).insert(any(AiDocument.class));
    }

    @Test
    void generateRejectsInvisibleProjectBeforeCallingModel() {
        doThrow(new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见"))
            .when(access).requireVisible(ACTOR, 77L);

        assertThatThrownBy(() -> controller.generate(new AiGenerateReq(77L, "PRD", "标题", "输入资料")))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND));
        verify(generation, never()).generate(any(), any());
    }

    @Test
    void reviewRejectsVersionOutsidePathChainBeforeMutation() {
        AiDocument pathRoot = document(10L, 100L, null, 1, "GENERATED");
        AiDocument otherVersion = document(20L, 200L, null, 1, "GENERATED");
        when(mapper.selectChain(10L)).thenReturn(List.of(pathRoot));
        when(mapper.selectById(20L)).thenReturn(otherVersion);
        when(access.requireVisible(ACTOR, 100L)).thenReturn("000000");

        assertThatThrownBy(() -> controller.review(10L, 20L))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void reviewAllowsVisibleVersionOnPathChain() {
        AiDocument root = document(10L, 100L, null, 1, "GENERATED");
        when(mapper.selectChain(10L)).thenReturn(List.of(root));
        when(mapper.selectById(10L)).thenReturn(root);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(access.requireVisible(ACTOR, 100L)).thenReturn("000000");

        AiDocument reviewed = controller.review(10L, 10L).getData();

        assertThat(reviewed.getStatus()).isEqualTo("REVIEWED");
        assertThat(reviewed.getReviewedBy()).isEqualTo(ACTOR.id());
        verify(mapper).update(isNull(), any());
    }

    @Test
    void archiveAndRejectRejectVersionOutsidePathChainBeforeMutation() {
        AiDocument pathRoot = document(10L, 100L, null, 1, "REVIEWED");
        when(mapper.selectChain(10L)).thenReturn(List.of(pathRoot));
        when(access.requireVisible(ACTOR, 100L)).thenReturn("000000");

        assertThatThrownBy(() -> controller.archive(10L, 20L))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
        assertThatThrownBy(() -> controller.reject(10L, 20L,
            new AiDocumentController.RejectReq("不同意")))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void diffRejectsVersionsFromDifferentChains() {
        AiDocument pathRoot = document(10L, 100L, null, 1, "REVIEWED");
        AiDocument otherVersion = document(20L, 200L, null, 1, "REVIEWED");
        when(mapper.selectChain(10L)).thenReturn(List.of(pathRoot));
        when(mapper.selectById(10L)).thenReturn(pathRoot);
        when(mapper.selectById(20L)).thenReturn(otherVersion);
        org.ruoyi.ipd.domain.Project project = new org.ruoyi.ipd.domain.Project();
        project.setTenantId("000000");
        when(access.requireVisible(ACTOR, null)).thenReturn("000000");
        when(projectRead.getVisibleById(100L, ACTOR)).thenReturn(project);

        assertThatThrownBy(() -> controller.diff(10L, 10L, 20L))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
    }

    private static AiDocument document(Long id, Long projectId, Long parentId,
                                       int versionNo, String status) {
        AiDocument row = new AiDocument();
        row.setId(id);
        row.setProjectId(projectId);
        row.setDocType("PRD");
        row.setParentVersionId(parentId);
        row.setVersionNo(versionNo);
        row.setStatus(status);
        row.setTitle("标题");
        row.setContent("正文");
        row.setContentSha256("hash");
        return row;
    }
}
