package org.ruoyi.ipd.service;

import java.util.List;
import org.ruoyi.ipd.domain.DeletionRequest;
import org.ruoyi.ipd.security.IpdActor;

/**
 * IDeletionRequestService 接口（paiban-05 接口化，实现见 {@link DeletionRequestServiceImpl}）。
 */
public interface IDeletionRequestService {

    public void setStateMachineGuard(org.ruoyi.ipd.service.StateMachineGuard stateMachineGuard);

    public void setClock(java.time.Clock clock);

    public DeletionRequest submit(IpdActor actor, String entityType, Long entityId, String snapshot, String reason);

    public DeletionRequest withdraw(Long requestId, Long requesterId);

    public DeletionRequest withdrawIfExistsOrNotFound(IpdActor actor, Long requestId);

    public DeletionRequest leaderDecision(IpdActor actor, Long requestId, boolean approve, String opinion);

    public DeletionRequest adminDecision(IpdActor actor, Long requestId, boolean approve, String opinion);

    public int escalateOverdueLeaderReview();

    public List<DeletionRequest> listOverdueAdminReview();

    public List<DeletionRequest> listByApplicant(Long applicantId);

    public List<DeletionRequest> listForReview(IpdActor actor);

}
