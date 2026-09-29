package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * OSS 文件实体（[SEC-FIX-HIGH-1.1-FOLLOWUP]）。
 * <p>共享 sys_oss 表（IPD 模块独立映射，避免与 ruoyi-system 模块横向依赖）。
 * 仅读取 url/fileName 字段用于 Gate 强制输出物守卫。
 * <p>create_by/create_time 等审计列统一由 {@link BaseEntity} 承接（sys_oss 真表五列齐备，
 * 列名与类型均匹配；create_by 为 bigint，原本地 String 声明属类型冲突，已移除）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sys_oss")
public class OssFileEntity extends BaseEntity {

    @TableId(value = "oss_id", type = IdType.ASSIGN_ID)
    private Long ossId;

    /** 访问 URL（resolveOssUrl 读取此字段写入 Gate.materialsUrl/meetingMinutesUrl）。 */
    private String url;

    private String fileName;

    private String originalName;

    private String fileSuffix;

    private String service;
}
