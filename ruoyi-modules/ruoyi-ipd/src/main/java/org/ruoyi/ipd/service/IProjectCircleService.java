package org.ruoyi.ipd.service;

import java.util.List;
import java.util.Map;
import org.ruoyi.ipd.security.IpdActor;

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
