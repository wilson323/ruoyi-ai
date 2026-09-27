package org.ruoyi.ipd.mapper;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.vo.AuditOperatorWindowStats;

/** AuditLog mapper */
@Mapper
public interface AuditLogMapper extends BaseMapperPlus<AuditLog, AuditLog> {

    /**
     * DEF-4 链重建专用：仅更新 prev_hash/curr_hash 两列（业务字段只读）。
     * <p>唯一合法调用方：{@code IAuditLogService.rebuildChain()}（超管修复工具，动作本身落审计）；
     * 业务代码禁止调用（G-02 只追加契约）。
     *
     * @param id       行主键
     * @param prevHash 重链后的前行哈希
     * @param currHash 重算后的本行哈希
     * @return 影响行数
     */
    @Update("UPDATE audit_logs SET prev_hash = #{prevHash}, curr_hash = #{currHash} WHERE id = #{id}")
    int updateChainHash(@Param("id") Long id, @Param("prevHash") String prevHash, @Param("currHash") String currHash);

    /**
     * AI-P3 #7 审计异常检测：按窗口 [windowStart, windowEnd) 聚合每操作人的写型动作数与
     * 非超管敏感动作数（GROUP BY 无阈值，阈值判定在 AuditAnomalyScanService 常量侧）。
     * <p>只读 SELECT——audit_logs 是 G-02 只追加表，本方法不得演化为任何 UPDATE/DELETE。
     * <p>NULL 角色按「非常规」计入规则 B（operator_role IS NULL 视同非超管，宁可多报不误漏：
     * 只报不拦场景下漏报代价高于偶发误报）。
     */
    @Select("SELECT operator_id AS operatorId, operator_name AS operatorName, operator_role AS operatorRole, "
        + "SUM(CASE WHEN action IN ('CREATE','UPDATE','DELETE','APPROVE','REJECT') THEN 1 ELSE 0 END) AS writeCount, "
        + "SUM(CASE WHEN action IN ('PERMANENT_DELETE','REBUILD_CHAIN','TRANSFER_SUPER_ADMIN') "
        + "AND (operator_role IS NULL OR operator_role <> 'SUPER_ADMIN') THEN 1 ELSE 0 END) AS sensitiveOffRoleCount "
        + "FROM audit_logs WHERE create_time >= #{windowStart} AND create_time < #{windowEnd} "
        + "GROUP BY operator_id, operator_name, operator_role")
    List<AuditOperatorWindowStats> selectOperatorWindowStats(@Param("windowStart") Date windowStart,
                                                             @Param("windowEnd") Date windowEnd);
}
