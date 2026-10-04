package org.ruoyi.ipd.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.ruoyi.common.core.service.ConfigService;
import org.ruoyi.common.core.utils.SpringUtils;
import org.ruoyi.common.oss.core.OssClient;
import org.ruoyi.common.oss.factory.OssFactory;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.mapper.SysOssMapper;
import org.ruoyi.system.service.impl.SysOssServiceImpl;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

@Tag("dev")
class DemandPrivateOssBoundaryTest {
    SysOssVo privateFile() {
        SysOssVo vo = new SysOssVo(); vo.setOssId(42L); vo.setService("ipd-demand-private"); vo.setFileName("secret/spec.pdf"); vo.setOriginalName("spec.pdf"); vo.setUrl("http://private-object"); return vo;
    }
    @Test void genericMetadataAndCachedMetadataNeverReturnPrivateFilesOrSignedUrls() throws Exception {
        SysOssMapper mapper = mock(SysOssMapper.class);
        SysOssServiceImpl service = spy(new SysOssServiceImpl(mapper, mock(ConfigService.class)));
        when(mapper.selectVoById(42L)).thenReturn(privateFile());
        assertThat(service.getById(42L)).isNull();
        // 模拟旧cache中已存的private元数据：通用消费者仍独立过滤。
        doReturn(privateFile()).when(service).getById(42L);
        try (var spring = mockStatic(SpringUtils.class); var factory = mockStatic(OssFactory.class)) {
            spring.when(() -> SpringUtils.getAopProxy(service)).thenReturn(service);
            assertThat(service.listByIds(List.of(42L))).isEmpty();
            assertThat(service.selectUrlByIds("42")).isEmpty();
            assertThat(service.selectByIds("42")).isEmpty();
            assertThatThrownBy(() -> service.download(42L,new MockHttpServletResponse())).isInstanceOf(org.ruoyi.common.core.exception.ServiceException.class);
            factory.verifyNoInteractions();
        }
    }
    @Test void internalMetadataDoesNotContainSignedOrPlainUrlAndChecksConfigKey() {
        SysOssMapper mapper = mock(SysOssMapper.class); SysOssServiceImpl service = new SysOssServiceImpl(mapper,mock(ConfigService.class));
        when(mapper.selectVoById(42L)).thenReturn(privateFile());
        assertThat(service.getPrivateById(42L,"ipd-demand-private").getUrl()).isNull();
        assertThatThrownBy(() -> service.getPrivateById(42L,"minio")).isInstanceOf(org.ruoyi.common.core.exception.ServiceException.class);
    }
    @Test void rollbackDeletesObjectWithoutDependingOnRolledBackMetadata() {
        SysOssMapper mapper = mock(SysOssMapper.class); SysOssServiceImpl service = new SysOssServiceImpl(mapper,mock(ConfigService.class));
        OssClient client = mock(OssClient.class);
        try (var factory = mockStatic(OssFactory.class)) {
            factory.when(() -> OssFactory.instance("ipd-demand-private")).thenReturn(client);
            service.cleanupUploadedObject(42L,"ipd-demand-private","secret/spec.pdf");
            verify(client).delete("secret/spec.pdf"); verify(mapper).deleteById(42L); verify(mapper,never()).selectVoById(anyLong());
        }
    }
    @Test void internalDownloadUsesStreamAndNeverGenericSignedDownload() throws Exception {
        SysOssMapper mapper = mock(SysOssMapper.class); SysOssServiceImpl service = spy(new SysOssServiceImpl(mapper,mock(ConfigService.class)));
        when(mapper.selectVoById(42L)).thenReturn(privateFile());
        OssClient client = mock(OssClient.class);
        @SuppressWarnings("unchecked") org.ruoyi.common.oss.core.WriteOutSubscriber<java.io.OutputStream> body = mock(org.ruoyi.common.oss.core.WriteOutSubscriber.class);
        when(client.download(eq("secret/spec.pdf"),any(java.util.function.Consumer.class))).thenReturn(body);
        doAnswer(invocation -> { ((java.io.OutputStream)invocation.getArgument(0)).write("private-body".getBytes()); return null; }).when(body).writeTo(any(java.io.OutputStream.class));
        try (var factory = mockStatic(OssFactory.class)) {
            factory.when(() -> OssFactory.instance("ipd-demand-private")).thenReturn(client);
            MockHttpServletResponse response = new MockHttpServletResponse();
            service.downloadPrivate(42L,"ipd-demand-private",response);
            assertThat(response.getContentAsString()).isEqualTo("private-body");
            verify(client).assertPrivateBucket(); verify(service,never()).download(anyLong(),any()); verify(service,never()).getById(anyLong());
        }
    }
}
