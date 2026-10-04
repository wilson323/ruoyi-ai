package org.ruoyi.ipd.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.IpdAgentMemory;

import java.util.List;

/**
 * IPD 智能体长期记忆候选区 Mapper。
 *
 * <p>{@code record()} 走官方 {@code StaticLongTermMemoryHook} 的异步调度线程，无会话上下文，
 * 故租户过滤双保险豁免（{@code tenant.excludes} 须登记 {@code ipd_agent_memory}）。
 *
 * <p><b>写入只走 {@link #insertIgnoreDuplicate} 的幂等口</b>：唯一键
 * {@code (project_id, person_id, source_digest)} 会在并发重放（同一次运行被记录两次）时挡掉重复，
 * 不靠「先查后插」——那有 TOCTOU 窗口。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface IpdAgentMemoryMapper extends BaseMapperPlus<IpdAgentMemory, IpdAgentMemory> {

    String COLUMNS = "id, project_id, person_id, run_id, kind, content, source_digest, status,"
        + " tenant_id, del_flag, create_dept, create_by, create_time, update_by, update_time, remark";

    /**
     * 幂等插入：命中唯一键时静默跳过（同一记忆被重复记录不产生第二行）。
     *
     * @return 实际插入行数；0 表示已存在
     */
    @Insert("INSERT IGNORE INTO ipd_agent_memory (" + COLUMNS + ")"
        + " VALUES (#{id}, #{projectId}, #{personId}, #{runId}, #{kind}, #{content},"
        + " #{sourceDigest}, #{status}, #{tenantId}, #{delFlag}, #{createDept}, #{createBy},"
        + " #{createTime}, #{updateBy}, #{updateTime}, #{remark})")
    int insertIgnoreDuplicate(IpdAgentMemory memory);

    /**
     * 召回：按「项目 + 人」取最近的可召回条目，废弃态不入。
     *
     * <p>硬性带 {@code project_id + person_id} 两个条件——这是「不同人之间记忆不互相召回」的唯一执行点，
     * 漏任一条件即为跨用户泄漏。
     */
    @Select("SELECT " + COLUMNS + " FROM ipd_agent_memory"
        + " WHERE project_id = #{projectId} AND person_id = #{personId}"
        + " AND del_flag = '0' AND status IN ('0', '1')"
        + " ORDER BY update_time DESC, id DESC LIMIT #{limit}")
    List<IpdAgentMemory> recallForScope(@Param("projectId") Long projectId,
                                       @Param("personId") Long personId,
                                       @Param("limit") int limit);

    /**
     * 晋升：候选 → 已晋升。带 status 前置条件，保证幂等且不会把废弃条改回来。
     */
    @Update("UPDATE ipd_agent_memory SET status = '1', update_time = NOW()"
        + " WHERE id = #{id} AND project_id = #{projectId} AND person_id = #{personId}"
        + " AND status = '0'")
    int promoteTo(@Param("id") Long id, @Param("projectId") Long projectId,
                  @Param("personId") Long personId);

    /**
     * 废弃。按项目与本人定位，可使候选或已晋升条目失效；不物理删除，保留审计线索。
     */
    @Update("UPDATE ipd_agent_memory SET status = '2', update_time = NOW()"
        + " WHERE id = #{id} AND project_id = #{projectId} AND person_id = #{personId}")
    int discard(@Param("id") Long id, @Param("projectId") Long projectId,
                @Param("personId") Long personId);

    /** 硬删除：仅供运维清理，不走业务路径。 */
    @Delete("DELETE FROM ipd_agent_memory WHERE id = #{id}"
        + " AND project_id = #{projectId} AND person_id = #{personId}")
    int purge(@Param("id") Long id, @Param("projectId") Long projectId,
              @Param("personId") Long personId);
}
