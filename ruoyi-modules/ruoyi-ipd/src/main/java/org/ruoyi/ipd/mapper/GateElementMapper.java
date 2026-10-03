package org.ruoyi.ipd.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.GateElement;

@Mapper
public interface GateElementMapper extends BaseMapperPlus<GateElement, GateElement> {
    /** 初始化只读预检包括软删除编号；全局唯一键不能因@TableLogic忽略历史占用。 */
    @org.apache.ibatis.annotations.Select("SELECT id, gate_code, element_code, element_name, pass_standard, is_veto, del_flag, status, enabled "
        + "FROM gate_review_elements WHERE gate_code IN ('G1','G2','G3','G4','G5') ORDER BY id")
    java.util.List<GateElement> selectSeedPreflightIncludingDeleted();
}