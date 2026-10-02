package org.ruoyi.chat.kernel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
@Tag("dev")
class KernelModelRequestParametersTest {
    @Test void parameterChangesInvalidateModelIdentity() {
        KernelModelRequest base = new KernelModelRequest("m", "openai", "key", "host", 0.3, 100, 10000);
        assertNotEquals(base.configurationIdentity(), new KernelModelRequest("m", "openai", "key", "host", 0.4, 100, 10000).configurationIdentity());
        assertNotEquals(base.configurationIdentity(), new KernelModelRequest("m", "openai", "key", "host", 0.3, 101, 10000).configurationIdentity());
        assertNotEquals(base.configurationIdentity(), new KernelModelRequest("m", "openai", "key", "host", 0.3, 100, 11000).configurationIdentity());
        assertEquals(new KernelModelRequest("m", "openai", "key", "host").configurationIdentity(), new KernelModelRequest("m", "openai", "key", "host", null, null, null).configurationIdentity());
    }
}
