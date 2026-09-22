package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.ProductGroup;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectCircleComment;
import org.ruoyi.ipd.domain.ProjectCirclePost;
import org.ruoyi.ipd.domain.ProjectFollower;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProjectCircleCommentMapper;
import org.ruoyi.ipd.mapper.ProjectCirclePostMapper;
import org.ruoyi.ipd.mapper.ProjectFollowerMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * IProjectCircleService 接口（paiban-05 接口化，实现见 {@link ProjectCircleService}）。
 */
public interface IProjectCircleService {

    /** 协作圈视图：项目 + 成员 + 动态（含评论）+ canManage。 */
    Map<String, Object> view(Long projectId, IpdActor actor);

    /** 可加为协作人候选：本组织 ACTIVE 且未在圈内。仅管理人可见。 */
    List<Map<String, Object>> candidates(Long projectId, IpdActor actor);

    /** 增加协作人（幂等 upsert 圈角色）+ 通知被加人。 */
    Map<String, Object> addMember(
        Long projectId,
        IpdActor actor,
        Long userId,
        String circleRole
    );

    /** 发动态（项目可见者均可；ARCHIVED 只读）。 */
    Long createPost(
        Long projectId,
        IpdActor actor,
        String content,
        String objectType,
        Long objectId
    );

    /** 评论（通知动态作者，本人不自我通知）。 */
    Long addComment(Long postId, IpdActor actor, String content, Long parentId);

}
