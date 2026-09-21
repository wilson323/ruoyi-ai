package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.PermanentDeleteAudit;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.PermanentDeleteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;
import java.util.Map;

/**
 * R149 batch2b C3：永久清除端点 /api/v1/admin/permanent-delete/{entityType}/{id}。
 *
 * <p>AC-C3 决策：最终清除要二次确认 + 审计永久保留。本 controller 仅暴露给 SUPER_ADMIN。
 *
 * <p>白名单（service 层二次校验）：{@code person|project|kpi_record}。
 * scenario 暂未建模——R149-batch2b 任务规划里虽然提及，但当前 code 库无 Scenario 实体，
 * 调用即抛 400（{@link PermanentDeleteService#ALLOWED_ENTITY_TYPES}）。后续 Scenario
 * 实体落地后需扩展白名单 + loadEntity/physicalDelete switch 分支。
 */
@RestController
@RequestMapping("/api/v1/admin/permanent-delete")
@RequiredArgsConstructor
@Slf4j
public class AdminPermanentDeleteController {

    private final PermanentDeleteService permanentDeleteService;
    private final IpdPermission ipdPermission;

    /**
     * 执行永久清除。
     *
     * @param entityType 白名单：person|project|kpi_record
     * @param id         实体主键
     * @param req        请求体（含 confirmCode）
     * @return audit row id
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PERMANENT_DELETE, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/{entityType}/{id}")
    public ApiV1Response<Map<String, Object>> execute(@PathVariable @Pattern(regexp = "person|project|kpi_record") String entityType,
                                                      @PathVariable @NotNull Long id,
                                                      @RequestBody @Valid ExecuteReq req) {
        IpdActor actor = ipdPermission.requireAdmin(); // service 层再校验一次
        Long auditId = permanentDeleteService.execute(actor, entityType, id, req.confirmCode());
        return ApiV1Response.ok(Map.of(
            "auditId", auditId,
            "entityType", entityType,
            "entityId", id,
            "operatorId", actor.id(),
            "operatorName", actor.name(),
            "permanentlyDeleted", true));
    }

    /**
     * 列出审计（仅超管；前端对账视图用）。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PERMANENT_DELETE, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/audit")
    public ApiV1Response<List<PermanentDeleteAudit>> audit(@RequestParam(required = false) String entityType,
                                                           @RequestParam(defaultValue = "50") int limit) {
        ipdPermission.requireAdmin();
        return ApiV1Response.ok(permanentDeleteService.listByEntityType(entityType, limit));
    }

    /**
     * 执行请求体。
     *
     * @param confirmCode 必须等于 {@link PermanentDeleteService#REQUIRED_CONFIRM_CODE}
     */
    public record ExecuteReq(
            @NotBlank @Size(max = 64) String confirmCode) {}
}
