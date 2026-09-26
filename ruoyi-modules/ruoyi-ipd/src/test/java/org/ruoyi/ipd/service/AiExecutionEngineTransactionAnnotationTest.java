package org.ruoyi.ipd.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.service.aiexec.AiExecResult;

import static org.assertj.core.api.Assertions.assertThat;

/** R221：引擎收尾写库方法必须 @Transactional(rollbackFor=Exception.class)。 */
@Tag("dev")
class AiExecutionEngineTransactionAnnotationTest {

    @Test
    void finalizeMethodIsTransactional() throws Exception {
        var m = AiExecutionEngine.class.getDeclaredMethod("finalizeTask",
            AiAgentTask.class, AiExecResult.class, String.class);
        var tx = m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.rollbackFor()).contains(Exception.class);
    }
}
