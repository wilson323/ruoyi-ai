package org.ruoyi.ipd.controller;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.ProductRetirement;
import org.ruoyi.ipd.dto.product.*;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.ProductRetirementService;
import org.springframework.web.bind.annotation.*;
/** Product retirement uses the real Person session and current line authority in the service. */
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/products/{id}/retirement")
public class ProductRetirementController {
 private final ProductRetirementService service;
 private final IpdPermission permission;
 @GetMapping public ApiV1Response<org.ruoyi.ipd.vo.RetirementView> get(@PathVariable Long id) {
  return ApiV1Response.ok(service.view(id,permission.requireInternal()));
 }
 @PostMapping public ApiV1Response<ProductRetirement> submit(@PathVariable Long id,@RequestBody RetirementSubmitReq req) {
  return ApiV1Response.ok(service.submit(id,req,permission.requireInternal()));
 }
 @PutMapping("/policy") public ApiV1Response<ProductRetirement> policy(@PathVariable Long id,@RequestBody RetirementPolicyReq req) {
  return ApiV1Response.ok(service.editPolicy(id,req,permission.requireInternal()));
 }
 @PostMapping("/decision") public ApiV1Response<ProductRetirement> decide(@PathVariable Long id,@RequestBody RetirementDecisionReq req) {
  return ApiV1Response.ok(service.decide(id,req,permission.requireInternal()));
 }
}
