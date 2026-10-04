package org.ruoyi.ipd.dto.product;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
public record RetirementPolicyReq(Integer expectedVersion, @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") Date marketingStopAt, @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") Date orderStopAt,
 @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") Date productionStopAt, @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") Date spareSupportStopAt, @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") Date softwareSupportStopAt, String softwareSupportPolicy) { }
