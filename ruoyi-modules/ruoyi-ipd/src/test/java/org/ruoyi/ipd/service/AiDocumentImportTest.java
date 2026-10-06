package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.factory.ResourceLoaderFactory;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.mapper.AiDocumentMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.service.knowledge.ResourceLoader;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人工审计「导入终稿」：外部成品文件解析后沿版本链追加待审核版本；
 * 授权/链校验/归档终态/空白解析等负向路径必须在写库前拒绝。
 */
@Tag("dev")
class AiDocumentImportTest {

    @BeforeAll
    static void initAiDocumentLambdaMetadata() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "ai-document-import"),
            AiDocument.class);
    }

    private static final IpdActor ACTOR = new IpdActor(9001L, "alice", "MARKET_PM", 100L);

    private final AiDocumentMapper mapper = mock(AiDocumentMapper.class);
    private final AiDocumentService documents = new AiDocumentService(mapper);
    private final IpdCopilotAccess access = mock(IpdCopilotAccess.class);
    private final ResourceLoaderFactory loaderFactory = mock(ResourceLoaderFactory.class);
    private final IAuditLogService auditLogService = mock(IAuditLogService.class);
    private final AiDocumentImportService service =
        new AiDocumentImportService(documents, loaderFactory, auditLogService);

    AiDocumentImportTest() {
        documents.setProjectAccess(access);
        when(access.requireVisible(ACTOR, 100L)).thenReturn("000000");
    }

    /** 单元素链：v1 即 HEAD。 */
    private void chainWithHead(AiDocument head) {
        when(mapper.selectChain(head.getId())).thenReturn(List.of(head));
        when(mapper.lockVersion(head.getId())).thenReturn(head);
        when(mapper.lockChild(head.getId())).thenReturn(null);
    }

    @Test
    void txtImportAppendsGeneratedVersionAndAudits() {
        AiDocument v1 = document(10L, 1, "GENERATED");
        chainWithHead(v1);
        MockMultipartFile file = new MockMultipartFile(
            "file", "评审终稿.txt", "text/plain", "人工定稿正文".getBytes(StandardCharsets.UTF_8));

        AiDocument created = service.importFinalVersion(ACTOR, 10L, 10L, file, null);

        assertThat(created.getVersionNo()).isEqualTo(2);
        assertThat(created.getStatus()).isEqualTo("GENERATED");
        assertThat(created.getContent()).isEqualTo("人工定稿正文");
        assertThat(created.getParentVersionId()).isEqualTo(10L);
        assertThat(created.getTitle()).isEqualTo("标题");
        verify(mapper).insert(created);
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("AI_DOC_IMPORT");
        assertThat(audit.getValue().getEntityId()).isEqualTo(created.getId());
    }

    @Test
    void mdImportKeepsRawMarkdownUtf8AndStripsBom() {
        AiDocument v1 = document(10L, 1, "GENERATED");
        chainWithHead(v1);
        MockMultipartFile file = new MockMultipartFile(
            "file", "spec.md", "text/markdown",
            ("\uFEFF# 章节\n正文").getBytes(StandardCharsets.UTF_8));

        AiDocument created = service.importFinalVersion(ACTOR, 10L, 10L, file, "新标题");

        assertThat(created.getContent()).isEqualTo("# 章节\n正文");
        assertThat(created.getTitle()).isEqualTo("新标题");
    }

    @Test
    void docxAndPdfRouteThroughExistingResourceLoaders() {
        AiDocument v1 = document(10L, 1, "GENERATED");
        chainWithHead(v1);
        ResourceLoader loader = mock(ResourceLoader.class);
        when(loader.getContent(any())).thenReturn("提取正文");
        when(loaderFactory.getLoaderByFileType("docx")).thenReturn(loader);

        MockMultipartFile docx = new MockMultipartFile(
            "file", "draft.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "fake-bytes".getBytes(StandardCharsets.UTF_8));
        assertThat(service.importFinalVersion(ACTOR, 10L, 10L, docx, null).getContent()).isEqualTo("提取正文");

        when(loaderFactory.getLoaderByFileType("pdf")).thenReturn(loader);
        MockMultipartFile pdf = new MockMultipartFile("file", "draft.PDF", "application/pdf",
            "fake-bytes".getBytes(StandardCharsets.UTF_8));
        assertThat(service.importFinalVersion(ACTOR, 10L, 10L, pdf, null).getContent()).isEqualTo("提取正文");
    }

    @Test
    void unsupportedExtensionRejectedBeforeChainRead() {
        MockMultipartFile file = new MockMultipartFile("file", "sheet.xlsx", "application/vnd.ms-excel",
            "bytes".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.importFinalVersion(ACTOR, 10L, 10L, file, null))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        verify(mapper, never()).selectChain(anyLong());
    }

    @Test
    void oversizedFileRejectedBeforeChainRead() {
        org.springframework.web.multipart.MultipartFile big = mock(org.springframework.web.multipart.MultipartFile.class);
        when(big.isEmpty()).thenReturn(false);
        when(big.getSize()).thenReturn(AiDocumentImportService.MAX_BYTES + 1);
        when(big.getOriginalFilename()).thenReturn("big.txt");

        assertThatThrownBy(() -> service.importFinalVersion(ACTOR, 10L, 10L, big, null))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        verify(mapper, never()).selectChain(anyLong());
    }

    @Test
    void baseVersionOffChainRejectedBeforeMutation() {
        AiDocument v1 = document(10L, 1, "GENERATED");
        when(mapper.selectChain(10L)).thenReturn(List.of(v1));

        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain",
            "正文".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.importFinalVersion(ACTOR, 10L, 20L, file, null))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
        verify(mapper, never()).insert(any(AiDocument.class));
    }

    @Test
    void archivedHeadRejectsImport() {
        AiDocument archived = document(10L, 1, "ARCHIVED");
        chainWithHead(archived);

        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain",
            "正文".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.importFinalVersion(ACTOR, 10L, 10L, file, null))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
        verify(mapper, never()).insert(any(AiDocument.class));
    }

    @Test
    void blankExtractedContentRejectedBeforeMutation() {
        AiDocument v1 = document(10L, 1, "GENERATED");
        chainWithHead(v1);
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain",
            "   ".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.importFinalVersion(ACTOR, 10L, 10L, file, null))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        verify(mapper, never()).insert(any(AiDocument.class));
    }

    @Test
    void invisibleProjectRejectedBeforeFileRead() throws Exception {
        AiDocument v1 = document(10L, 1, "GENERATED");
        when(mapper.selectChain(10L)).thenReturn(List.of(v1));
        doThrow(new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见"))
            .when(access).requireVisible(ACTOR, 100L);

        MockMultipartFile file = mock(MockMultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(1L);
        when(file.getOriginalFilename()).thenReturn("a.txt");
        assertThatThrownBy(() -> service.importFinalVersion(ACTOR, 10L, 10L, file, null))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND));
        verify(file, never()).getBytes();
    }

    @Test
    void tooLongTitleRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain",
            "正文".getBytes(StandardCharsets.UTF_8));
        String longTitle = IntStream.range(0, 201).mapToObj(i -> "x").collect(Collectors.joining());

        assertThatThrownBy(() -> service.importFinalVersion(ACTOR, 10L, 10L, file, longTitle))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
    }

    /** 端点权限必须复用改版码（ipd:ai-document:edit），不新增权限面。 */
    @Test
    void importEndpointReusesRevisePermission() throws Exception {
        Method method = org.ruoyi.ipd.controller.AiDocumentImportController.class
            .getMethod("importFinal", Long.class, Long.class, String.class,
                org.springframework.web.multipart.MultipartFile.class);
        cn.dev33.satoken.annotation.SaCheckPermission annotation =
            method.getAnnotation(cn.dev33.satoken.annotation.SaCheckPermission.class);
        assertThat(annotation.value()).containsExactly(IpdPermissionCode.OPERATION_AI_DOCUMENT_REVISE);
    }

    private static AiDocument document(Long id, int versionNo, String status) {
        AiDocument row = new AiDocument();
        row.setId(id);
        row.setProjectId(100L);
        row.setDocType("PRD");
        row.setParentVersionId(null);
        row.setVersionNo(versionNo);
        row.setStatus(status);
        row.setTitle("标题");
        row.setContent("正文");
        row.setContentSha256("hash");
        return row;
    }
}
