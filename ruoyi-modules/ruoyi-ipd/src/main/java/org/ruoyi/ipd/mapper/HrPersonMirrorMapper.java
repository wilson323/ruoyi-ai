package org.ruoyi.ipd.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.HrPersonMirror;

/**
 * HR 人员档案镜像 Mapper（D10 评审落地，2026-09-29）。
 *
 * <p>对应表 {@code hr_person_mirror}（HR 真源字段快照；非登录体系）。
 */
@Mapper
public interface HrPersonMirrorMapper extends BaseMapperPlus<HrPersonMirror, HrPersonMirror> {
}
