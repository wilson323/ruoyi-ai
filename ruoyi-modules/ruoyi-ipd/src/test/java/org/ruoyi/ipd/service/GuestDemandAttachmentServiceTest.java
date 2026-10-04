package org.ruoyi.ipd.service;

import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.ipd.domain.*;
import org.ruoyi.ipd.mapper.*;
import org.ruoyi.ipd.security.*;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Tag("dev")
class GuestDemandAttachmentServiceTest {
    RequirementMapper mapper; ISysOssService oss; IpdPermission permission;
    ProductLineMapper lines; ProductLineMemberMapper members;
    GuestDemandAttachmentService service; Requirement row; String token; IpdAuthSession authSession; Person sessionPerson; MockedStatic<TenantHelper> tenant;
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    @BeforeEach void setUp() {
        tenant = mockStatic(TenantHelper.class); tenant.when(TenantHelper::isEnable).thenReturn(false);
        mapper = mock(RequirementMapper.class); oss = mock(ISysOssService.class); permission = mock(IpdPermission.class);
        lines = mock(ProductLineMapper.class); members = mock(ProductLineMemberMapper.class);
        service = new GuestDemandAttachmentService(mapper, oss, permission, lines, members);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        authSession = mock(IpdAuthSession.class); sessionPerson = new Person(); sessionPerson.setId(7L); sessionPerson.setTenantId("000000");
        when(authSession.currentPerson()).thenReturn(sessionPerson); service.setAuthSession(authSession);
        token = GuestDemandAttachmentService.newUploadToken();
        row = new Requirement().setId(1L).setStatus("SUBMITTED").setQueryCode("AB12CD34").setUploadTokenHash(GuestDemandAttachmentService.tokenHash(token));
        row.setCreateTime(Date.from(NOW));
        when(mapper.selectOne(any())).thenReturn(row); when(mapper.updateById(any(Requirement.class))).thenReturn(1);
        SysOssVo stored = new SysOssVo(); stored.setOssId(42L); stored.setService("ipd-demand-private"); stored.setFileName("private/object.pdf");
        when(oss.uploadPrivate(any(org.springframework.web.multipart.MultipartFile.class),eq("ipd-demand-private"))).thenReturn(stored);
        when(oss.getPrivateById(42L, "ipd-demand-private")).thenReturn(stored); 
        when(permission.requireInternal()).thenReturn(new IpdActor(7L,"真实人员","MARKET_PM",9L));
    }
    @AfterEach void tearDown() { tenant.close(); if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization(); }
    MockMultipartFile file() { return new MockMultipartFile("file", "spec.pdf", "application/pdf", "%PDF-contract".getBytes()); }
    @Test void uploadStoresRealOssAndRetriesAreIdempotent() throws Exception {
        var first = service.upload("AB12CD34", token, "filekey01", file());
        var second = service.upload("AB12CD34", token, "filekey01", file());
        assertThat(second).isEqualTo(first); verify(oss, times(1)).uploadPrivate(any(org.springframework.web.multipart.MultipartFile.class),eq("ipd-demand-private"));
        assertThat(row.getAttachmentsJson()).contains("42").doesNotContain(token);
        assertThat(GuestDemandAttachmentService.publicEntries(row)).hasSize(1);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(GuestDemandAttachmentService.publicEntries(row))).doesNotContain("ossId", "sha256", "filekey01", "url");
    }
    @Test void uploadAllowedImmediatelyBeforeDeadlineAndDeniedAtExactlyTwentyFourHours() throws Exception {
        service.setClock(Clock.fixed(NOW.plusSeconds(24L * 3600).minusMillis(1), ZoneOffset.UTC));
        service.upload("AB12CD34", token, "filekey01", file());
        service.setClock(Clock.fixed(NOW.plusSeconds(24L * 3600), ZoneOffset.UTC));
        assertThatThrownBy(() -> service.upload("AB12CD34", token, "filekey01", file())).isInstanceOf(IpdBusinessException.class);
        verify(oss, times(1)).uploadPrivate(any(org.springframework.web.multipart.MultipartFile.class), eq("ipd-demand-private"));
    }
    @Test void anonymousPortalUploadUsesTrustedDefaultTenantWhenMultiTenantEnabled() throws Exception {
        tenant.when(TenantHelper::isEnable).thenReturn(true);
        tenant.when(TenantHelper::getTenantId).thenReturn(null);
        service.upload("AB12CD34", token, "filekey01", file());
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<Requirement>> scope = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).selectOne(scope.capture());
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "guest-demand-attachment"), Requirement.class);
        assertThat(scope.getValue().getSqlSegment()).contains("tenant_id");
        assertThat(((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Requirement>) scope.getValue()).getParamNameValuePairs().values()).contains("000000");
        verify(oss).uploadPrivate(any(org.springframework.web.multipart.MultipartFile.class), eq("ipd-demand-private"));
        verifyNoInteractions(permission);
    }
    @Test void wrongTokenCannotUploadOrReadStorage() {
        assertThatThrownBy(() -> service.upload("AB12CD34", GuestDemandAttachmentService.newUploadToken(), "filekey01", file())).isInstanceOf(IpdBusinessException.class);
        verifyNoInteractions(oss);
    }
    @Test void queryCodeAloneNeverAllowsUpload() {
        assertThatThrownBy(() -> service.upload("AB12CD34", null, "filekey01", file())).isInstanceOf(IpdBusinessException.class); verifyNoInteractions(oss);
    }
    @Test void acceptedAndExpiredDemandCannotUpload() {
        row.setStatus("ACCEPTED"); assertThatThrownBy(() -> service.upload("AB12CD34", token, "filekey01", file())).isInstanceOf(IpdBusinessException.class);
        row.setStatus("SUBMITTED"); row.setCreateTime(Date.from(NOW.minusSeconds(25L*3600)));
        assertThatThrownBy(() -> service.upload("AB12CD34", token, "filekey01", file())).isInstanceOf(IpdBusinessException.class); verifyNoInteractions(oss);
    }
    @Test void rejectsOversizeAndUnsupportedFile() {
        var large = mock(org.springframework.web.multipart.MultipartFile.class); when(large.isEmpty()).thenReturn(false); when(large.getSize()).thenReturn(GuestDemandAttachmentService.MAX_BYTES+1);
        assertThatThrownBy(() -> service.upload("AB12CD34", token, "filekey01", large)).isInstanceOf(IpdBusinessException.class);
        assertThatThrownBy(() -> service.upload("AB12CD34", token, "filekey01", new MockMultipartFile("file", "malware.exe", "application/octet-stream", new byte[]{1}))).isInstanceOf(IpdBusinessException.class); verifyNoInteractions(oss);
    }
    @Test void rejectsSixthFileAndIdempotencyKeyReuseWithDifferentBytes() throws Exception {
        for (int i=0;i<5;i++) service.upload("AB12CD34", token, "filekey0"+i, file());
        assertThatThrownBy(() -> service.upload("AB12CD34", token, "filekey05", file())).isInstanceOf(IpdBusinessException.class);
        assertThatThrownBy(() -> service.upload("AB12CD34", token, "filekey00", new MockMultipartFile("file", "spec.pdf", "application/pdf", new byte[]{3}))).isInstanceOf(IpdBusinessException.class);
        verify(oss,times(5)).uploadPrivate(any(org.springframework.web.multipart.MultipartFile.class),eq("ipd-demand-private"));
    }
    @Test void failedDbAssociationCleansStorageAndPreservesFailure() {
        when(mapper.updateById(any(Requirement.class))).thenThrow(new IllegalStateException("write failed")); doThrow(new IllegalStateException("cleanup failed")).when(oss).cleanupUploadedObject(42L,"ipd-demand-private","private/object.pdf");
        assertThatThrownBy(() -> service.upload("AB12CD34", token, "filekey01", file())).hasMessage("write failed"); verify(oss).cleanupUploadedObject(42L,"ipd-demand-private","private/object.pdf");
    }
    @Test void transactionRollbackCleansUploadedFile() throws Exception {
        TransactionSynchronizationManager.initSynchronization(); service.upload("AB12CD34", token, "filekey01", file());
        for (var sync: TransactionSynchronizationManager.getSynchronizations()) sync.afterCompletion(1);
        verify(oss).cleanupUploadedObject(42L,"ipd-demand-private","private/object.pdf");
    }
    @Test void sameGroupUnassignedPersonCannotRead() { assertThatThrownBy(() -> service.list(1L)).isInstanceOf(IpdBusinessException.class); verifyNoInteractions(oss); }
    @Test void assignedProcessorCanDownloadButWrongKeyCannot() throws Exception {
        row.setMarketPmId(7L); service.upload("AB12CD34", token, "filekey01", file());
        MockHttpServletResponse response = new MockHttpServletResponse(); service.download(1L,"filekey01",response);
        verify(oss).downloadPrivate(eq(42L),eq("ipd-demand-private"),eq(response)); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThatThrownBy(() -> service.download(1L,"unknown",response)).isInstanceOf(IpdBusinessException.class);
    }
    @Test void lineLeaderLosesAccessAfterExitOrRemoval() {
        row.setProductLineId(11L); ProductLine line = new ProductLine(); line.setId(11L); line.setStatus("ACTIVE"); line.setLeaderPersonId(7L);
        when(lines.selectOne(any())).thenReturn(line); when(members.selectCount(any())).thenReturn(1L); assertThat(service.list(1L)).isEmpty();
        when(members.selectCount(any())).thenReturn(0L); assertThatThrownBy(() -> service.list(1L)).isInstanceOf(IpdBusinessException.class);
        when(members.selectCount(any())).thenReturn(1L); line.setLeaderPersonId(8L); assertThatThrownBy(() -> service.list(1L)).isInstanceOf(IpdBusinessException.class);
    }
    @Test void ipdAdminReadsUsingPersonTenantWithoutPlatformSession() {
        tenant.when(TenantHelper::isEnable).thenReturn(true); tenant.when(TenantHelper::getTenantId).thenReturn(null);
        when(permission.requireInternal()).thenReturn(new IpdActor(7L,"admin","SUPER_ADMIN",9L));
        assertThat(service.list(1L)).isEmpty();
        verify(authSession).currentPerson();
    }
    @Test void personTenantScopesAdminQueryAndDoesNotFallBackToPlatformOrDefault() {
        tenant.when(TenantHelper::isEnable).thenReturn(true); tenant.when(TenantHelper::getTenantId).thenReturn("000000");
        sessionPerson.setTenantId("tenant-person");
        when(permission.requireInternal()).thenReturn(new IpdActor(7L,"admin","SUPER_ADMIN",9L));
        when(mapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.list(1L)).isInstanceOf(IpdBusinessException.class);
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<Requirement>> scope = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).selectOne(scope.capture());
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "guest-demand-attachment-person"), Requirement.class);
        assertThat(scope.getValue().getSqlSegment()).contains("tenant_id");
        var values = ((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Requirement>) scope.getValue()).getParamNameValuePairs().values();
        assertThat(values).contains("tenant-person").doesNotContain("000000");
        verifyNoInteractions(oss);
    }
    @Test void missingTenantFailsClosedEvenForAdmin() {
        tenant.when(TenantHelper::isEnable).thenReturn(true); tenant.when(TenantHelper::getTenantId).thenReturn(null);
        when(permission.requireInternal()).thenReturn(new IpdActor(7L,"admin","SUPER_ADMIN",9L));
        sessionPerson.setTenantId(" ");
        assertThatThrownBy(() -> service.list(1L)).isInstanceOf(IpdBusinessException.class); verify(mapper,never()).selectOne(any());
    }
}
