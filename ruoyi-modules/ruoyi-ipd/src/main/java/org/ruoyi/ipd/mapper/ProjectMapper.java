package org.ruoyi.ipd.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.Project;

/**
 * 项目 Mapper
 */
@Mapper
public interface ProjectMapper extends BaseMapperPlus<Project, Project> {

    /**
     * R179-P0（2026-09-22）：原生 SQL 取当年最大编码序号，必须绕过 @TableLogic——
     * MP 自动查询对软删行（del_flag=1）不可见，但 uk_projects_code 是物理唯一索引
     * 仍占用编码；若取号只见活行，序号会回退到已软删的编码上（实测 PRJ-2026-033
     * 软删后 nextCode 生成 033，INSERT 撞物理 uk，8 次重试确定性全败，创建项目
     * API 整体不可用）。取号含软删行（序号单调只增）物理 uk 才可能不撞。
     * 脏数据（尾缀非数字）CAST 得 0，不影响 max，与旧 Java 端跳过语义等价。
     */
    @Select("SELECT MAX(CAST(SUBSTRING(code, CHAR_LENGTH(CONCAT('PRJ-', #{year}, '-')) + 1) AS UNSIGNED)) "
        + "FROM projects WHERE code LIKE CONCAT('PRJ-', #{year}, '-%')")
    Integer selectMaxCodeSeqByYear(@Param("year") int year);

    /** 小阶段游标 CAS；租户、软删与旧游标均参与条件，避免并发覆盖或跨租户更新。 */
    @Update("UPDATE projects SET current_sub_stage_code = #{nextCode}, "
        + "sub_stage_version = sub_stage_version + 1, last_sub_stage_gate_result = 'PASSED', "
        + "update_by = #{actorId}, update_time = CURRENT_TIMESTAMP "
        + "WHERE id = #{projectId} AND tenant_id <=> #{tenantId} AND del_flag = '0' "
        + "AND current_stage = #{expectedStage} AND status NOT IN ('SUSPENDED', 'ARCHIVED') "
        + "AND sub_stage_version = #{expectedVersion} "
        + "AND current_sub_stage_code <=> #{previousCode}")
    int advanceSubStage(@Param("projectId") Long projectId,
                        @Param("tenantId") String tenantId,
                        @Param("expectedStage") String expectedStage,
                        @Param("previousCode") String previousCode,
                        @Param("nextCode") String nextCode,
                        @Param("expectedVersion") Long expectedVersion,
                        @Param("actorId") Long actorId);
}
