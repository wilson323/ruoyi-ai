package org.ruoyi.workflow.workflow.node;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.workflow.entity.WorkflowComponent;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.workflow.WfNodeState;
import org.ruoyi.workflow.workflow.WfState;
import org.ruoyi.workflow.workflow.node.mailSend.MailSendNode;
import org.ruoyi.workflow.workflow.node.mailSend.MailSendNodeConfig;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class MailSendUnknownEffectTest {
    @AfterEach void restoreSleeper() { NodeFailurePolicy.sleeper = seconds -> Thread.sleep(seconds * 1000L); }

    private static class FixtureMail extends MailSendNode {
        FixtureMail() { super(component(), definition(), new WfState(new User(), new ArrayList<>(),
            "mail-fixture", 1L, "fixture", null, 1L), new WfNodeState()); }
        private static WorkflowComponent component() { var c = new WorkflowComponent(); c.setName("MailSend"); return c; }
        private static WorkflowNode definition() { var n = new WorkflowNode(); n.setUuid("mail-fixture"); n.setTitle("邮件"); return n; }
        @Override public void initInput() { }
        @Override public String getNodeMessageTemplate(String key) { return ""; }
        @Override public void notifyAndStoreMessage(WfState state, String message) { }
        @Override protected <T> T checkAndGetConfig(Class<T> type) {
            var c = new MailSendNodeConfig();
            var smtp = new MailSendNodeConfig.SmtpConfig(); smtp.setHost("fixture.invalid"); smtp.setPort(465); c.setSmtp(smtp);
            var sender = new MailSendNodeConfig.SenderConfig(); sender.setMail("from@fixture.invalid"); sender.setName("fixture"); sender.setPassword("fixture"); c.setSender(sender);
            c.setToMails("to@fixture.invalid"); c.setSubject("fixture"); c.setContent("fixture"); return type.cast(c);
        }
    }

    @Test void actualMailCatchIsFailureAndDoesNotResendUnknownDelivery() {
        AtomicInteger sends = new AtomicInteger();
        NodeFailurePolicy.sleeper = seconds -> fail("副作用不得进入退避");
        try (var mocked = mockConstruction(JavaMailSenderImpl.class, (sender, context) -> {
            when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
            doAnswer(call -> { sends.incrementAndGet(); throw new MailSendException("private remote exception"); })
                .when(sender).send(any(MimeMessage.class));
        })) {
            var node = new FixtureMail();
            assertThrows(RuntimeException.class, () -> node.process(null, null));
            assertEquals(1, sends.get()); assertEquals(1, mocked.constructed().size());
            assertEquals("节点执行结果尚未确认，请先核对业务结果；未自动重试", node.getState().getProcessStatusRemark());
            assertTrue(node.getState().getOutputs().stream().noneMatch(x -> String.valueOf(x).contains("private remote exception")));
        }
    }

    @Test void actualMailSuccessSendsOnce() {
        AtomicInteger sends = new AtomicInteger();
        try (var mocked = mockConstruction(JavaMailSenderImpl.class, (sender, context) -> {
            when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
            doAnswer(call -> { sends.incrementAndGet(); return null; }).when(sender).send(any(MimeMessage.class));
        })) {
            var result = new FixtureMail().process(null, null);
            assertFalse(result.isError()); assertEquals(1, sends.get()); assertEquals(1, mocked.constructed().size());
        }
    }
}
