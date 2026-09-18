package org.ruoyi.example.gen-test;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.exception.ServiceException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 标准 Service 单测模板
 *
 * 适用场景：纯 Service 单元测试，无需 Spring 容器
 * 关键 funs: MockitoExtension + InjectMocks + AssertJ
 * 引用参考: references/tag-filtering-rules.md, references/mock-validity-3-types.md
 *
 * 复制本文件到目标 Service 测试目录：
 *   cp examples/service-test-template.java \
 *      ruoyi-modules/ruoyi-system/src/test/java/org/ruoyi/system/service/impl/ExampleServiceTest.java
 *
 * 复制后改类名 + import 路径即可使用。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ExampleServiceTest {

    @Mock private ExampleMapper baseMapper;
    @InjectMocks private ExampleService service;

    @Test
    @DisplayName("主路径 - 命中返回")
    void getById_hit() {
        ExampleEntity cached = new ExampleEntity();
        cached.setId(1L);
        cached.setName("hit");
        when(baseMapper.selectById(1L)).thenReturn(cached);

        ExampleEntity actual = service.getById(1L);

        assertThat(actual.getName()).isEqualTo("hit");
        verify(baseMapper, times(1)).selectById(1L);
    }

    @Test
    @DisplayName("参数校验 - null 抛 ServiceException")
    void getById_null_throws() {
        assertThatThrownBy(() -> service.getById(null))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("id 不能为空");
    }
}