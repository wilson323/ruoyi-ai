package org.ruoyi.service.vector.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.MyBatisSystemException;
import org.ruoyi.domain.vo.knowledge.KnowledgeAttachSource;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;

import java.sql.SQLDataException;

/**
 * 向量检索出处不再把 create_by 按数字读。
 */
@Tag("dev")
class WeaviateAttachSourceMappingTest {

    @Test
    @DisplayName("create_by 为 wb-kb-20260806 时仍返回海康威视出处，不整段失败")
    void stringCreatorStillReturnsSource() {
        KnowledgeAttachMapper mapper = mock(KnowledgeAttachMapper.class);
        when(mapper.selectOne(any())).thenThrow(numericMappingFailure());
        when(mapper.selectSourceByKnowledgeAndDocId("1", "f4bfc9d03c0b292a9d2b1d784d3b8604"))
            .thenReturn(row("海康威视.md", "wb-kb-20260806"));

        String source = WeaviateVectorStoreStrategy.sourceName(
            mapper, "1", "f4bfc9d03c0b292a9d2b1d784d3b8604");

        assertEquals("海康威视.md", source);
        assertEquals("wb-kb-20260806",
            mapper.selectSourceByKnowledgeAndDocId("1", "f4bfc9d03c0b292a9d2b1d784d3b8604").getCreateBy());
        assertThrows(NumberFormatException.class, () -> Long.parseLong("wb-kb-20260806"));
        verify(mapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("数字创建人仍按原文字可读")
    void numericCreatorStaysReadable() {
        KnowledgeAttachMapper mapper = mock(KnowledgeAttachMapper.class);
        when(mapper.selectSourceByKnowledgeAndDocId("1", "doc-num")).thenReturn(row("数字创建人.md", "900103"));

        assertEquals("数字创建人.md", WeaviateVectorStoreStrategy.sourceName(mapper, "1", "doc-num"));
        assertEquals("900103", mapper.selectSourceByKnowledgeAndDocId("1", "doc-num").getCreateBy());
        assertEquals(900103L, Long.parseLong(mapper.selectSourceByKnowledgeAndDocId("1", "doc-num").getCreateBy()));
    }

    @Test
    void sourceIsBoundToTheQueriedKnowledgeAndDocument() {
        KnowledgeAttachMapper mapper = mock(KnowledgeAttachMapper.class);
        when(mapper.selectSourceByKnowledgeAndDocId("1", "same-doc"))
            .thenReturn(row("本项目资料.md", "source"));
        when(mapper.selectSourceByKnowledgeAndDocId("2", "same-doc"))
            .thenReturn(row("他项目资料.md", "source"));
        assertEquals("本项目资料.md", WeaviateVectorStoreStrategy.sourceName(mapper, "1", "same-doc"));
        verify(mapper, never()).selectSourceByKnowledgeAndDocId("2", "same-doc");
    }

    private static KnowledgeAttachSource row(String name, String createBy) {
        KnowledgeAttachSource row = new KnowledgeAttachSource();
        row.setName(name);
        row.setCreateBy(createBy);
        return row;
    }

    private static MyBatisSystemException numericMappingFailure() {
        SQLDataException sql = new SQLDataException(
            "Cannot determine value type from string 'wb-kb-20260806'");
        return new MyBatisSystemException(new PersistenceException(sql));
    }
}
