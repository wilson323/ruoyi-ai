package org.ruoyi.service.vector.impl;

import io.agentscope.core.embedding.EmbeddingModel;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.v1.graphql.model.GraphQLError;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;
import io.weaviate.client.v1.graphql.query.Get;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.config.VectorStoreProperties;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class WeaviateReadOnlySearchTest {
    @Test
    void nullAndRemoteErrorsAreFaultsWithoutRemoteDetails() {
        assertThrows(ServiceException.class, () -> WeaviateVectorStoreStrategy.requireSearchResponse(null));
        Result<GraphQLResponse> error = mock(Result.class);
        when(error.hasErrors()).thenReturn(true);
        assertEquals("知识库向量查询不可用", assertThrows(ServiceException.class,
            () -> WeaviateVectorStoreStrategy.requireSearchResponse(error)).getMessage());
        GraphQLResponse response = GraphQLResponse.builder().data(Map.of()).errors(new GraphQLError[]{
            GraphQLError.builder().message("http://private.invalid Authorization: secret").build()
        }).build();
        assertThrows(ServiceException.class, () -> WeaviateVectorStoreStrategy.requireSearchResponse(
            new Result<>(200, response, null)));
        assertThrows(ServiceException.class, () -> WeaviateVectorStoreStrategy.requireSearchResponse(
            new Result<>(200, null, null)));
    }

    @Test
    void searchDoesNotCreateSchemaAndSuccessfulEmptyArrayIsNoHit() throws Exception {
        VectorStoreProperties properties = new VectorStoreProperties();
        properties.getWeaviate().setClassname("LocalKnowledge");
        WeaviateVectorStoreStrategy strategy = spy(new WeaviateVectorStoreStrategy(properties, null, null, null));
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embedding.embed(any())).thenReturn(Mono.just(new double[]{1, 0}));
        doReturn(embedding).when(strategy).getEmbeddingModel("fixture");
        WeaviateClient client = mock(WeaviateClient.class, RETURNS_DEEP_STUBS);
        Get query = mock(Get.class, RETURNS_SELF);
        when(client.graphQL().get()).thenReturn(query);
        when(query.run()).thenReturn(new Result<>(200, GraphQLResponse.builder()
            .data(Map.of("Get", Map.of("LocalKnowledge1", List.of()))).build(), null));
        Field field = WeaviateVectorStoreStrategy.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(strategy, client);
        QueryVectorBo input = new QueryVectorBo();
        input.setKid("1");
        input.setQuery("产品资料");
        input.setEmbeddingModelName("fixture");
        input.setMaxResults(4);
        assertTrue(strategy.search(input).isEmpty());
        verify(strategy, never()).createSchema(any(), any());
        verify(client, never()).schema();
    }
}
