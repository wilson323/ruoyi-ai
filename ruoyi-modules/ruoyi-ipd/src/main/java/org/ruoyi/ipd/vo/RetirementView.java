package org.ruoyi.ipd.vo;
import org.ruoyi.ipd.domain.ProductRetirement;
import java.util.Date;
import java.util.List;
/** Permission flags are current authorization projections, not grants. */
public record RetirementView(ProductRetirement retirement, boolean canSubmit, boolean canEditPolicy,
 boolean canDecide, List<History> history, Effects effects) {
 public record History(Long operatorId,String operatorName,String action,String reason,
  @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") Date createTime) { }
 public record Effects(boolean marketingStopped, boolean orderStopped, boolean productionStopped,
  boolean spareSupportStopped, boolean softwareSupportStopped, String lifecyclePhase) { }
}

