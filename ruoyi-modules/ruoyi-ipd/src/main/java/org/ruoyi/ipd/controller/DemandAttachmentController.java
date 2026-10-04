package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.GuestDemandAttachmentService;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;

/** 每次清单读取与下载均执行需求对象权限，不继承需求列表的角色范围。 */
@RestController
@RequestMapping("/api/v1/demands/{id}/attachments")
@RequiredArgsConstructor
public class DemandAttachmentController {
    private final GuestDemandAttachmentService attachments;
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_GROUP, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping
    public ApiV1Response<List<GuestDemandAttachmentService.AttachmentView>> list(@PathVariable Long id) {
        return ApiV1Response.ok(attachments.list(id));
    }
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_GROUP, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/{key}/download")
    public void download(@PathVariable Long id, @PathVariable String key, HttpServletResponse response) throws IOException {
        attachments.download(id, key, response);
    }
}
