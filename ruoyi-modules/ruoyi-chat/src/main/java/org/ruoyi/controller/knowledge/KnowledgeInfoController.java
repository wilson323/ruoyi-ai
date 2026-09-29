package org.ruoyi.controller.knowledge;

import java.util.List;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.*;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.domain.bo.knowledge.KnowledgeInfoBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import org.ruoyi.common.idempotent.annotation.RepeatSubmit;
import org.ruoyi.common.log.annotation.Log;
import org.ruoyi.common.web.core.BaseController;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.validate.AddGroup;
import org.ruoyi.common.core.validate.EditGroup;
import org.ruoyi.common.log.enums.BusinessType;
import org.ruoyi.common.excel.utils.ExcelUtil;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;

/**
 * 知识库
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/info")
public class KnowledgeInfoController extends BaseController {

    private final IKnowledgeInfoService knowledgeInfoService;

    /**
     * C 口收敛（getInfo 读面）专用：queryById 被 ws 消息线程（MultiKnowledgeAugmentor）与
     * @Async 解析链共享，Service 层内嵌单参 Gate 会误伤非 HTTP 调用方，故该端点的
     * ownership 校验收敛在 Controller 入口（判据仍在 Gate，非手写）。
     */
    private final KnowledgeAccessGate knowledgeAccessGate;

    /**
     * 查询知识库列表
     */
    @SaCheckPermission("system:info:list")
    @GetMapping("/list")
    public TableDataInfo<KnowledgeInfoVo> list(KnowledgeInfoBo bo, PageQuery pageQuery) {
        return knowledgeInfoService.queryPageList(bo, pageQuery);
    }

    /**
     * 导出知识库列表
     */
    @SaCheckPermission("system:info:export")
    @Log(title = "知识库", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(KnowledgeInfoBo bo, HttpServletResponse response) {
        List<KnowledgeInfoVo> list = knowledgeInfoService.queryList(bo);
        ExcelUtil.exportExcel(list, "知识库", KnowledgeInfoVo.class, response);
    }

    /**
     * 获取知识库详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("system:info:query")
    @GetMapping("/{id}")
    public R<KnowledgeInfoVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        // C 口收敛（B0 审计破坏面 C）：getInfo 按 kid 直读任意库元数据，读面判据过 Gate
        //（owned || share=1 可见即可；他人私有库在此拒绝）。收敛点在 Controller 的原因
        // 见字段 javadoc——queryById 是 ws/@Async 共享底层，不能内嵌 Gate。
        knowledgeAccessGate.checkRetrievalAccess(id);
        return R.ok(knowledgeInfoService.queryById(id));
    }

    /**
     * 新增知识库
     */
    @SaCheckPermission("system:info:add")
    @Log(title = "知识库", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody KnowledgeInfoBo bo) {
            bo.setUserId(LoginHelper.getUserId());
        return toAjax(knowledgeInfoService.insertByBo(bo));
    }

    /**
     * 修改知识库
     */
    @SaCheckPermission("system:info:edit")
    @Log(title = "知识库", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody KnowledgeInfoBo bo) {
        return toAjax(knowledgeInfoService.updateByBo(bo));
    }

    /**
     * 删除知识库
     *
     * @param ids 主键串
     */
    @SaCheckPermission("system:info:remove")
    @Log(title = "知识库", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(knowledgeInfoService.deleteWithValidByIds(List.of(ids), true));
    }
}
