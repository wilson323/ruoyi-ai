package org.ruoyi.ipd.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 只读项目所属产品线名称。不改项目，不改产品线。
 */
@Mapper
public interface ProductLineNameMapper {

    /**
     * 读取项目当前产品线名称。
     *
     * @param projectId 项目主键
     * @return line_name；项目不存在或未挂产品线时为 null
     */
    @Select("""
        SELECT pl.line_name
        FROM projects p
        LEFT JOIN product_lines pl ON pl.id = p.product_line_id AND pl.del_flag = '0'
        WHERE p.id = #{projectId}
        LIMIT 1
        """)
    String selectLineName(@Param("projectId") Long projectId);

    /**
     * 读取项目产品线已记下的知识库服务标识。
     *
     * @param projectId 项目主键
     * @return mcp_service_id；未记或未挂产品线时为 null
     */
    @Select("""
        SELECT pl.mcp_service_id
        FROM projects p
        LEFT JOIN product_lines pl ON pl.id = p.product_line_id AND pl.del_flag = '0'
        WHERE p.id = #{projectId}
        LIMIT 1
        """)
    String selectServiceId(@Param("projectId") Long projectId);
}
