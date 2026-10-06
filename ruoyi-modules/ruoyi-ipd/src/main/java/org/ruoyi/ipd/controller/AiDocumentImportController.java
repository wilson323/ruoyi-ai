package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.AiDocumentImportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * AI 文档「导入终稿」端点 POST /api/v1/ai-documents/{id}/import（人工审计环节）。
 *
 * <p>审计人把系统外定稿（docx/pdf/md/txt）导入为版本链上的新待审核版本，
 * 之后沿用原审核/定档链传给下一节点——本端点只是 revise 的文件入口，
 * 权限复用改版码 {@code ipd:ai-document:edit}，不新增权限、不改审核语义。
 * 独立控制器是为了不动 {@link AiDocumentController} 构造签名（兄弟测试直连三参构造）。
 */
@RestController
@RequestMapping("/api/v1/ai-documents")
@RequiredArgsConstructor
public class AiDocumentImportController {

    private final AiDocumentImportService aiDocumentImportService;
    private final IpdPermission ipdPermission;

    /**
     * 导入终稿：解析文件正文后基于 baseVersionId（必须为当前链头）追加 v(n+1)，status=GENERATED 待审核。
     *
     * @param id 链上任一版本行 ID（文档锚点）
     * @param baseVersionId 调用方声明的基准版本（乐观锁；非 HEAD → 409）
     * @param title 新标题（可空，空则沿用链头标题）
     * @param file 终稿文件（docx/pdf/md/txt，≤20MB）
     * @return 新版本行
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AI_DOCUMENT_REVISE, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping(value = "/{id}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiV1Response<AiDocument> importFinal(@PathVariable Long id,
                                                 @RequestParam("baseVersionId") Long baseVersionId,
                                                 @RequestParam(value = "title", required = false) String title,
                                                 @RequestPart("file") MultipartFile file) {
        // title 长度在服务层校验（本类未挂 @Validated，参数级约束注解不会生效，不依赖它）
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(aiDocumentImportService.importFinalVersion(actor, id, baseVersionId, file, title));
    }
}
