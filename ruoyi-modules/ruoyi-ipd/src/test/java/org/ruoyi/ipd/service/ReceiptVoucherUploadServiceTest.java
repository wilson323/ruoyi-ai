package org.ruoyi.ipd.service;

import cn.hutool.crypto.digest.DigestUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回款凭证上传：项目必须存在，超限或格式不对时不触达对象存储，成功时回读 URL 并给出服务端哈希。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReceiptVoucherUploadServiceTest {

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ISysOssService ossService;
    @InjectMocks
    private ReceiptVoucherUploadService service;

    @Test
    @DisplayName("pdf 上传成功：回读 URL，哈希为文件字节的 SHA-256")
    void uploadsPdfAndReturnsServerHash() throws Exception {
        Project project = new Project();
        project.setDelFlag("0");
        when(projectMapper.selectById(9L)).thenReturn(project);
        byte[] bytes = "voucher".getBytes(StandardCharsets.UTF_8);
        MultipartFile file = file("回单.pdf", bytes);
        SysOssVo stored = new SysOssVo();
        stored.setOssId(42L);
        stored.setUrl("http://oss/receipt/42.pdf");
        when(ossService.upload(file)).thenReturn(stored);
        when(ossService.getById(42L)).thenReturn(stored);

        Map<String, String> view = service.upload(9L, file);

        assertThat(view.get("voucherUrl")).isEqualTo("http://oss/receipt/42.pdf");
        assertThat(view.get("voucherHash")).isEqualTo(DigestUtil.sha256Hex(bytes));
        assertThat(view.get("fileName")).isEqualTo("回单.pdf");
    }

    @Test
    @DisplayName("非白名单扩展名在触达对象存储前拒绝")
    void rejectsExecutable() {
        Project project = new Project();
        project.setDelFlag("0");
        when(projectMapper.selectById(9L)).thenReturn(project);

        assertThatThrownBy(() -> service.upload(9L, file("a.exe", new byte[] {1})))
            .isInstanceOf(IpdBusinessException.class);
        verify(ossService, never()).upload(any(MultipartFile.class));
    }

    @Test
    @DisplayName("超过 20MB 拒绝且不上传")
    void rejectsOversize() {
        Project project = new Project();
        project.setDelFlag("0");
        when(projectMapper.selectById(9L)).thenReturn(project);
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(ReceiptVoucherUploadService.MAX_BYTES + 1);

        assertThatThrownBy(() -> service.upload(9L, file)).isInstanceOf(IpdBusinessException.class);
        verify(ossService, never()).upload(any(MultipartFile.class));
    }

    @Test
    @DisplayName("项目不存在拒绝")
    void rejectsMissingProject() {
        when(projectMapper.selectById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.upload(9L, file("a.pdf", new byte[] {1})))
            .isInstanceOf(IpdBusinessException.class);
        verify(ossService, never()).upload(any(MultipartFile.class));
    }

    private static MultipartFile file(String name, byte[] bytes) throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(bytes.length == 0);
        when(file.getSize()).thenReturn((long) bytes.length);
        when(file.getOriginalFilename()).thenReturn(name);
        when(file.getBytes()).thenReturn(bytes);
        return file;
    }
}
