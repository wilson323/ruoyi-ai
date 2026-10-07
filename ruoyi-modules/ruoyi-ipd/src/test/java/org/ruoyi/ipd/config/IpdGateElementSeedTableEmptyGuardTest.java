package org.ruoyi.ipd.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.domain.GateElement;
import org.ruoyi.ipd.mapper.GateElementMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ②刀 G2 验收：Gate 要素种子「仅当表空」守卫。
 *
 * <p>核心口径是三件事：空表恰灌 33 条；非空表（污染数据）一行不插一行不改写；重复调用幂等。
 * 其中「非空表不改写」用 updateTime 哨兵证明——哨兵值在 run() 前后逐行比对，
 * 任何隐式改写（哪怕不是显式 updateById）都会让哨兵漂移而让本测试变红。
 */
@Tag("dev")
class IpdGateElementSeedTableEmptyGuardTest {

    /** 污染库规模：33 条规范 + 54 条非规范。 */
    private static final int POLLUTED_ROWS = 87;

    /** updateBy 哨兵：任何隐式改写都会把它冲掉。 */
    private static final Long SENTINEL_UPDATE_BY = 999_999L;

    @Test
    @DisplayName("空表：恰插 33 条，status 全 published，否决项恰 14 条")
    void emptyTableSeedsExactly33PublishedElementsWith14VetoItems() {
        var mapper = mock(GateElementMapper.class);
        when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(List.of());
        when(mapper.insert(any(GateElement.class))).thenReturn(1);

        new IpdGateElementSeedInitializer(mapper).run(null);

        var captor = ArgumentCaptor.forClass(GateElement.class);
        verify(mapper, times(33)).insert(captor.capture());
        List<GateElement> rows = captor.getAllValues();

        assertThat(rows).hasSize(33);
        assertThat(rows.stream().filter(x -> "1".equals(x.getIsVeto())).count()).isEqualTo(14L);
        assertThat(rows.stream().filter(x -> "0".equals(x.getIsVeto())).count()).isEqualTo(19L);
        // 各 Gate 否决分布 5/4/0/3/2（2026-10-07 逐行解析 SEED_ELEMENTS 实测校正）。
        // 单独钉住分布，防止「总数 14 对但分布错了」这类只查总数查不出的漂移。
        assertThat(rows.stream().filter(x -> "1".equals(x.getIsVeto()) && x.getGateCode().equals("G1")).count()).isEqualTo(5L);
        assertThat(rows.stream().filter(x -> "1".equals(x.getIsVeto()) && x.getGateCode().equals("G2")).count()).isEqualTo(4L);
        assertThat(rows.stream().filter(x -> "1".equals(x.getIsVeto()) && x.getGateCode().equals("G3")).count()).isZero();
        assertThat(rows.stream().filter(x -> "1".equals(x.getIsVeto()) && x.getGateCode().equals("G4")).count()).isEqualTo(3L);
        assertThat(rows.stream().filter(x -> "1".equals(x.getIsVeto()) && x.getGateCode().equals("G5")).count()).isEqualTo(2L);
        assertThat(rows).allMatch(x -> "published".equals(x.getStatus()));
        assertThat(rows).allMatch(x -> "1".equals(x.getEnabled()));
        assertThat(rows).allMatch(x -> "0".equals(x.getDelFlag()));
        // 非否决项必须 vetoDualRequired='0'——GateElementService:539 反向校验「'1' 仅适用否决项」，
        // 故本断言对全部 33 行成立。否决项的双签取值是另一条口径，见下方 @Disabled 的 P0-05 登记。
        assertThat(rows).allMatch(x -> "0".equals(x.getVetoDualRequired()));
        // 规范编号集合 G1-1…G5-7 逐条对得上，且无重复编号。
        assertThat(rows.stream().map(GateElement::getElementCode)).doesNotHaveDuplicates()
            .containsExactlyInAnyOrder(
                "G1-1", "G1-2", "G1-3", "G1-4", "G1-5", "G1-6", "G1-7",
                "G2-1", "G2-2", "G2-3", "G2-4", "G2-5", "G2-6",
                "G3-1", "G3-2", "G3-3", "G3-4", "G3-5",
                "G4-1", "G4-2", "G4-3", "G4-4", "G4-5", "G4-6", "G4-7", "G4-8",
                "G5-1", "G5-2", "G5-3", "G5-4", "G5-5", "G5-6", "G5-7");
    }

    @Test
    @DisplayName("已有 87 行污染数据：插入 0 条，既有行 0 改写（updateTime 哨兵）")
    void pollutedTableSeedsNothingAndLeavesEveryExistingRowUntouched() {
        var mapper = mock(GateElementMapper.class);
        List<GateElement> polluted = pollutedRows(POLLUTED_ROWS);
        List<Date> sentinels = polluted.stream().map(GateElement::getUpdateTime).toList();
        when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(polluted);

        new IpdGateElementSeedInitializer(mapper).run(null);

        // 一行都不插
        verify(mapper, never()).insert(any(GateElement.class));
        verify(mapper, never()).updateById(any(GateElement.class));
        verify(mapper, never()).deleteById(any(Long.class));
        verify(mapper, never()).deleteBatchIds(any(java.util.Collection.class));
        verify(mapper, never()).update(any(GateElement.class), any());
        // 既有行 0 改写：哨兵法逐行比对 updateTime
        List<Date> after = polluted.stream().map(GateElement::getUpdateTime).toList();
        assertThat(after).isEqualTo(sentinels);
        assertThat(polluted).allMatch(x -> SENTINEL_UPDATE_BY.equals(x.getUpdateBy()));
    }

    @Test
    @DisplayName("重复调用 run()：第二次插入 0 条（幂等）")
    void secondRunOnSeededTableInsertsNothing() {
        var mapper = mock(GateElementMapper.class);
        when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(List.of());
        when(mapper.insert(any(GateElement.class))).thenReturn(1);
        var initializer = new IpdGateElementSeedInitializer(mapper);

        initializer.run(null);
        var captor = ArgumentCaptor.forClass(GateElement.class);
        verify(mapper, times(33)).insert(captor.capture());
        List<GateElement> seeded = new ArrayList<>(captor.getAllValues());

        // 第二次：库已非空（33 行），守卫应拦下
        when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(seeded);
        initializer.run(null);

        verify(mapper, times(33)).insert(any(GateElement.class)); // 总数仍为 33，第二次零插入
        verify(mapper, never()).updateById(any(GateElement.class));
    }

    @Test
    @DisplayName("守卫计数必须含软删除行：裸 @Select 的 SQL 里不得出现 del_flag 过滤")
    void guardCountIncludesSoftDeletedRows() throws Exception {
        String sql = GateElementMapper.class.getMethod("selectSeedPreflightIncludingDeleted")
            .getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0];
        // 无 del_flag 过滤 ⇒ 计数覆盖软删行；一旦被加上过滤，全软删的表会被误判为空表而重插 33 条。
        assertThat(sql).doesNotContain("del_flag =", "del_flag='", "del_flag = '");
        // 实体侧 @TableLogic 是 selectCount 口径的陷阱所在，此处留档。
        assertThat(GateElement.class.getDeclaredField("delFlag")
            .getAnnotation(com.baomidou.mybatisplus.annotation.TableLogic.class)).isNotNull();
    }

    /**
     * 造 87 行污染数据：前 33 行用规范编号，后 54 行用非规范编号（含软删行），每行打哨兵。
     *
     * <p>哨兵字段 updateTime/updateBy 继承自 BaseEntity，而 GateElement 用的是普通
     * {@code @Builder}（不是 {@code @SuperBuilder}），继承字段不在 builder 里，
     * 必须用 setter 逐行写入。
     */
    private static List<GateElement> pollutedRows(int total) {
        String[] canonical = {
            "G1-1", "G1-2", "G1-3", "G1-4", "G1-5", "G1-6", "G1-7",
            "G2-1", "G2-2", "G2-3", "G2-4", "G2-5", "G2-6",
            "G3-1", "G3-2", "G3-3", "G3-4", "G3-5",
            "G4-1", "G4-2", "G4-3", "G4-4", "G4-5", "G4-6", "G4-7", "G4-8",
            "G5-1", "G5-2", "G5-3", "G5-4", "G5-5", "G5-6", "G5-7"};
        var rows = new ArrayList<GateElement>();
        long base = 1_700_000_000_000L;
        for (int i = 0; i < total; i++) {
            boolean softDeleted = i >= 40; // 含软删行
            GateElement row = GateElement.builder()
                .id((long) i + 1)
                .gateCode("G" + (i % 5 + 1))
                .elementCode(i < canonical.length ? canonical[i] : "LEGACY-" + i)
                .elementName("污染行-" + i)
                .passStandard("污染标准-" + i)
                .isVeto("0")
                .enabled("1")
                .status(i < canonical.length ? "published" : "draft")
                .delFlag(softDeleted ? "1" : "0")
                .build();
            row.setUpdateTime(new Date(base + i));
            row.setUpdateBy(SENTINEL_UPDATE_BY);
            rows.add(row);
        }
        return rows;
    }
}
