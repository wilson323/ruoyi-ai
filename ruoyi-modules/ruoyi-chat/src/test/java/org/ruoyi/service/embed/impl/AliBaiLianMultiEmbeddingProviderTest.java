package org.ruoyi.service.embed.impl;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.domain.dto.MultiModalInput;
import org.ruoyi.enums.ModalityType;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class AliBaiLianMultiEmbeddingProviderTest {
    @Test
    void unsupportedInputsNeverReachModel() {
        var provider = new AliBaiLianMultiEmbeddingProvider();
        EmbeddingModel model = mock(EmbeddingModel.class);
        ReflectionTestUtils.setField(provider, "nativeModel", model);
        assertThrows(UnsupportedOperationException.class, () -> provider.embedVideo("https://example.test/video").block());
        for (var input : new MultiModalInput[]{
            MultiModalInput.builder().videoUrl("https://example.test/video").build(),
            MultiModalInput.builder().text("text").imageUrl("https://example.test/image").build(),
            MultiModalInput.builder().multiImageUrls(new String[]{"https://example.test/image"}).build()}) {
            assertThrows(UnsupportedOperationException.class, () -> provider.embedMultiModal(input).block());
        }
        assertEquals(java.util.Set.of(ModalityType.TEXT, ModalityType.IMAGE), provider.getSupportedModalities());
        verifyNoInteractions(model);
    }

    @Test
    void textAndImageUseNativeBlocks() {
        var provider = new AliBaiLianMultiEmbeddingProvider();
        EmbeddingModel model = mock(EmbeddingModel.class);
        ReflectionTestUtils.setField(provider, "nativeModel", model);
        when(model.embed(any(ContentBlock.class))).thenReturn(Mono.just(new double[]{1, 2}));
        assertArrayEquals(new double[]{1, 2}, provider.embedMultiModal(MultiModalInput.builder().text("text").build()).block());
        assertArrayEquals(new double[]{1, 2}, provider.embedImage("https://example.test/image").block());
        assertArrayEquals(new double[]{1, 2}, provider.embedMultiModal(MultiModalInput.builder()
            .imageData(new byte[]{1, 2}).imageMimeType("image/png").build()).block());
        verify(model).embed(any(TextBlock.class));
        verify(model, times(2)).embed(any(ImageBlock.class));
    }

    @Test
    void missingInputOrImageMimeFailsWithoutOutbound() {
        var provider = new AliBaiLianMultiEmbeddingProvider();
        assertThrows(IllegalArgumentException.class, () -> provider.embedMultiModal(null).block());
        assertThrows(IllegalArgumentException.class, () -> provider.embedMultiModal(MultiModalInput.builder().build()).block());
        assertThrows(IllegalArgumentException.class, () -> provider.embedMultiModal(MultiModalInput.builder().imageData(new byte[]{1}).build()).block());
        assertThrows(IllegalStateException.class, () -> provider.embedMultiModal(MultiModalInput.builder().text("text").build()).block());
    }
}
