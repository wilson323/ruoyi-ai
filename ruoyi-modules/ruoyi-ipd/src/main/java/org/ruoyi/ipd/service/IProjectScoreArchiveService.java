package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.ProductGroup;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.ProjectScore;
import org.ruoyi.ipd.domain.ProjectScoreRecord;
import org.ruoyi.ipd.domain.SystemConfigVersion;
import org.ruoyi.ipd.dto.ProjectScoreSubmitReq;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.ProjectScoreMapper;
import org.ruoyi.ipd.mapper.ProjectScoreRecordMapper;
import org.ruoyi.ipd.mapper.SystemConfigVersionMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.vo.ProjectScoreView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IProjectScoreArchiveService 接口（paiban-05 接口化，实现见 {@link ProjectScoreArchiveService}）。
 */
public interface IProjectScoreArchiveService {

    /** 独立提交一个评分组件；ARCHIVED 状态后再次提交只能产生新版本。 */
    ProjectScoreView submit(ProjectScoreSubmitReq request);

    /** * P3-2.2 归档区列表查询：仅超管（SEC-API-01 同严，禁止 StpUtil 旁路）。 */
    /** * */
    /** * <p>只读事务（{@code readOnly=true}）+ MySQL 一致性快照优化；过滤已提交评分 */
    /** * （status SUBMITTED/FINALIZED）+ 时间窗 [fromMonth, toMonth]（闭区间，按 scoredAt 倒序）。 */
    /** * */
    /** * @param projectId 项目 ID（必填） */
    /** * @param fromMonth 起始月份（含） */
    /** * @param toMonth   截止月份（含） */
    /** * @return 时间窗内已提交的 ProjectScore 列表（按 scoredAt DESC） */
    List<ProjectScore> listArchive(Long projectId, YearMonth fromMonth, YearMonth toMonth);

    /** 按最高共同版本重算结算视图；不读取当前规则。 */
    ProjectScoreView settleVersion(Long projectId, Long personId);

    /** 只读当前共同版本；历史重算调用同一方法，结果可重复。 */
    ProjectScoreView view(Long projectId, Long personId);

}
