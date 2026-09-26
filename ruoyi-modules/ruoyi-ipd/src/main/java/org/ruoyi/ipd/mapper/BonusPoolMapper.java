package org.ruoyi.ipd.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.BonusPool;

/**
 * 奖金池 Mapper（P3-4.2/4.3 AC-INC-16/17/18/19/20/21）
 */
@Mapper
public interface BonusPoolMapper extends BaseMapperPlus<BonusPool, BonusPool> {

    /**
     * R219（看板卡 2bef6e0e）：按 projectId 查任意状态奖金池，用于 compute 前置拦截
     * 「已 freeze（CONFIRMED）池二次 compute 撞 UNIQUE uk_bp_project → 裸 500/90001」。
     * 取代旧 selectByProjectIdAndStatus（仅查 DRAFT，对 CONFIRMED 池有洞）——
     * uk 每项目只允许一行，任意状态预查即可完全覆盖 DRAFT 分支。
     *
     * <p>故意不过滤 del_flag：{@code uk_bp_project(project_id)} 不含软删标记位，
     * 软删行同样会撞唯一键——预检查询必须与 uk 的碰撞集合严格一致，否则拦截有洞。
     * 命中后由 Service 层抛 STATE_CONFLICT（HTTP 409）而非落入 DB 兜底 500。
     *
     * @param projectId 项目 ID
     * @return 命中实体（含 DRAFT / CONFIRMED / DISTRIBUTED / 软删行）；无则 null
     */
    @Select("SELECT * FROM bonus_pools"
          + " WHERE project_id = #{projectId}"
          + " AND tenant_id = '000000'"
          + " ORDER BY id DESC LIMIT 1")
    BonusPool selectByProjectIdAnyStatus(@Param("projectId") Long projectId);
}
