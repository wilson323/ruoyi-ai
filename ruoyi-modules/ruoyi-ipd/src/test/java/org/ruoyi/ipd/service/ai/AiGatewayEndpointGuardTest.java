package org.ruoyi.ipd.service.ai;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
@Tag("dev")
class AiGatewayEndpointGuardTest {
    @Test void delegatesExistingOutboundCheckWithoutModelCall() {
        AiChatClient client = mock(AiChatClient.class);
        new AiGateway(client).validateEndpoint("https://example.invalid");
        verify(client).ssrfCheck("https://example.invalid");
        verifyNoMoreInteractions(client);
    }
    @Test void preservesExistingGuardRejection() {
        AiChatClient client = mock(AiChatClient.class);
        doThrow(new IllegalArgumentException("blocked")).when(client).ssrfCheck("http://127.0.0.1");
        assertThrows(IllegalArgumentException.class, () -> new AiGateway(client).validateEndpoint("http://127.0.0.1"));
    }
}
