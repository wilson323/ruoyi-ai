package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.GateElement;
import org.ruoyi.ipd.mapper.AuditLogMapper;
import org.ruoyi.ipd.mapper.GateElementMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;

/**
 * IGateElementService 接口（paiban-05 接口化，实现见 {@link GateElementService}）。
 */
public interface IGateElementService {

    /** 业务/超管列表：仅启用要素；草稿与归档天然不可见（enabled='0'）。 */
    List<GateElement> listByGate(String gateCode);

    /** * 新建要素：一律落为草稿（status=draft/version=0/enabled='0'），publish 后才对业务可见。 */
    /** * 请求里的 enabled 仅作旧客户端兼容字段，不采纳。 */
    /** * */
    /** * <p>SEC-REV-GATE-ELEMENT-01：审计字段绑定 actor —— createBy/updateBy 取 actor.id()， */
    /** * createTime/updateTime 清空交由 MyBatis-Plus MetaObjectHandler 填充服务端权威时间。 */
    GateElement create(GateElement e, IpdActor actor);

    /** * 编辑定义（白名单合并）。仅草稿可改定义；已发布/归档携定义字段即 409； */
    /** * 仅 enabled 启停的补丁放行已发布行（停用/恢复管理路径）。 */
    GateElement update(GateElement patch, IpdActor actor);

    /** 停用（禁删：在途 gate_element_results 引用，G-02 证据链）。发布/草稿态均可停用。 */
    GateElement disable(Long id, IpdActor actor);

    /** 发布：draft → published，enabled 置 '1'，发布版本递增。 */
    GateElement publish(Long id, IpdActor actor);

    /** 归档（终态）：draft/published → archived，enabled 置 '0'；复活请用 copy。 */
    GateElement archive(Long id, IpdActor actor);

    /** * 复制：以任一状态要素为蓝本克隆出新草稿（新编码必填且唯一），定义字段全量拷贝， */
    /** * status=draft/version=0/enabled='0'。归档要素借此复活修改后重新发布。 */
    GateElement copy(Long id, String newElementCode, IpdActor actor);

    /** * 历史恢复：仅草稿可回滚；从指定审计行的 before_data 定义快照恢复字段。 */
    /** * 已发布要素请走 copy（对已发布直接回滚同属定义编辑，一并 409）。 */
    GateElement revert(Long id, Long auditLogId, IpdActor actor);

    /** * 复制为副本草稿（P2-5.x「复制」按钮后端）：编码自动生成、名称追加「（副本）」， */
    /** * status=draft / version=0 / enabled='0'；源要素零改动，每次调用产生一行新副本。 */
    GateElement duplicate(Long id, IpdActor actor);

    /** * 归档恢复（P2-5.x「恢复」按钮后端）：archived → draft，enabled='0'，version 保留 */
    /** * （版本号是该要素身份的单调发布计数，再次 publish 得 version+1）。恢复落草稿而非 */
    /** * 直接上线，避免绕过发布评审；仅归档态可恢复，其余状态 409（同态重放第二次即 409）。 */
    GateElement restore(Long id, IpdActor actor);

    /** 管理视图：返回全部生命周期状态（含草稿/归档/停用），仅供超管后台要素管理页。 */
    List<GateElement> listForManage(String gateCode);

}
