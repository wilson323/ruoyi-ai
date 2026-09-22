package org.ruoyi.ipd.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.domain.GateElement;
import org.ruoyi.ipd.mapper.GateElementMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 页47 事故链哨兵：Seed 值域映射必须正确（Y→1 否决、N→0），且 enabled/status/version/dual 归一。
 *
 * <p>本测试是「会红哨兵」——在三处历史错误版本上均失败：
 * ① main 旧版 .isVeto(def[4]) 直接把 Y/N 存进 is_veto（14 否决位存成 'Y'，业务侧 '1'.equals 判不出）；
 * ② ed997168 版 .isVeto("1".equals(def[4])) 因 def[4] 恒为 Y/N 永不等于 "1"，14 否决位全灭成 '0'；
 * ③ 仅 .isVeto("Y".equals(def[4]) ? "1" : "0") 正确映射出 14 否决位。
 */
@Tag("dev")
class IpdGateElementSeedInitializerTest {

    @Test
    @DisplayName("Seed 33 项：14 否决位 isVeto='1'、19 非否决 '0'，无 Y/N 泄漏，enabled/status/version/dual 归一")
    void seedMapsVetoAndNormalizesFields() throws Exception {
        GateElementMapper mapper = mock(GateElementMapper.class);
        when(mapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(mapper.insert(any(GateElement.class))).thenReturn(1);

        new IpdGateElementSeedInitializer(mapper).run(null);

        ArgumentCaptor<GateElement> captor = ArgumentCaptor.forClass(GateElement.class);
        verify(mapper, times(33)).insert(captor.capture());
        List<GateElement> all = captor.getAllValues();

        assertThat(all).hasSize(33);
        // 核心：14 否决位（Y→1），19 非否决（N→0）
        assertThat(all.stream().filter(x -> "1".equals(x.getIsVeto())).count()).isEqualTo(14L);
        assertThat(all.stream().filter(x -> "0".equals(x.getIsVeto())).count()).isEqualTo(19L);
        // 无字面 Y/N 泄漏（main 旧版病根）
        assertThat(all).noneMatch(x -> "Y".equals(x.getIsVeto()) || "N".equals(x.getIsVeto()));
        // 归一字段（main 旧版 enabled='Y'/status='PUBLISHED'/version=0/dual='N' 全错）
        assertThat(all).allMatch(x -> "1".equals(x.getEnabled()));
        assertThat(all).allMatch(x -> "published".equals(x.getStatus()));
        assertThat(all).allMatch(x -> Integer.valueOf(1).equals(x.getVersion()));
        assertThat(all).allMatch(x -> "0".equals(x.getVetoDualRequired()));
    }
}
