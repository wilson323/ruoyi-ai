package org.ruoyi.chat.kernel.tool;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class PublicDestinationGuardTest {
    private static void assertBlocked(Object url) {
        assertEquals(PublicDestinationGuard.BLOCKED_REASON, PublicDestinationGuard.blockReason(url), String.valueOf(url));
    }

    @Test void internalAndMetadataDestinationsAreBlocked() {
        assertBlocked("http://127.0.0.1/");
        assertBlocked("http://127.0.0.1:8080/admin");
        assertBlocked("http://localhost/");
        assertBlocked("http://169.254.169.254/latest/meta-data/");
        assertBlocked("http://10.0.0.5/");
        assertBlocked("http://172.16.3.4/");
        assertBlocked("http://192.168.1.1/");
        assertBlocked("http://100.64.1.1/");
        assertBlocked("http://0.0.0.0/");
        assertBlocked("http://[::1]/");
        assertBlocked("http://[fd00::1]/");
        assertBlocked("http://[fe80::1]/");
        assertBlocked("http://[::ffff:127.0.0.1]/");
    }

    @Test void ipLiteralVariantsResolveToTheSameBlockedAddress() {
        // JDK 解析器与 HttpClient 出站连接同一个：十进制/十六进制字面量都指向 127.0.0.1。
        // 八进制写法 0177.0.0.1 在 JDK 里按十进制 177.0.0.1 解析（公网地址），连接器同样连到它，故不在此断言。
        assertBlocked("http://2130706433/");
        assertBlocked("http://0x7f000001/");
    }

    @Test void malformedSchemeUserinfoAndEmptyInputsAreBlocked() {
        assertBlocked(null);
        assertBlocked("");
        assertBlocked("   ");
        assertBlocked("ftp://93.184.216.34/");
        assertBlocked("file:///etc/passwd");
        assertBlocked("http://user:pw@93.184.216.34/");
        assertBlocked("http:///no-host");
        assertBlocked("not a url");
        assertBlocked(42);
    }

    @Test void unresolvableHostIsBlockedFailClosed() {
        assertEquals(PublicDestinationGuard.BLOCKED_REASON, PublicDestinationGuard.blockReason(
            "http://example.test/", host -> { throw new java.net.UnknownHostException(host); }));
    }

    @Test void hostnameResolvingToInternalAddressIsBlocked() throws Exception {
        var internal = java.net.InetAddress.getByAddress("intranet.example", new byte[] {10, 0, 0, 5});
        var metadata = java.net.InetAddress.getByAddress("meta.example", new byte[] {(byte) 169, (byte) 254, (byte) 169, (byte) 254});
        assertEquals(PublicDestinationGuard.BLOCKED_REASON,
            PublicDestinationGuard.blockReason("http://intranet.example/", host -> new java.net.InetAddress[] {internal}));
        assertEquals(PublicDestinationGuard.BLOCKED_REASON,
            PublicDestinationGuard.blockReason("http://meta.example/", host -> new java.net.InetAddress[] {metadata}));
    }

    @Test void anyInternalAddressAmongSeveralRecordsBlocksTheWholeHost() throws Exception {
        var publicIp = java.net.InetAddress.getByAddress("mixed.example", new byte[] {(byte) 93, (byte) 184, (byte) 216, 34});
        var loopback = java.net.InetAddress.getByAddress("mixed.example", new byte[] {127, 0, 0, 1});
        assertEquals(PublicDestinationGuard.BLOCKED_REASON, PublicDestinationGuard.blockReason(
            "http://mixed.example/", host -> new java.net.InetAddress[] {publicIp, loopback}));
    }

    @Test void hostnameResolvingToPublicAddressIsAllowed() throws Exception {
        var publicIp = java.net.InetAddress.getByAddress("ok.example", new byte[] {(byte) 93, (byte) 184, (byte) 216, 34});
        assertNull(PublicDestinationGuard.blockReason("https://ok.example/page", host -> new java.net.InetAddress[] {publicIp}));
    }

    @Test void publicIpLiteralIsAllowed() {
        assertNull(PublicDestinationGuard.blockReason("http://93.184.216.34/"));
        assertNull(PublicDestinationGuard.blockReason("https://93.184.216.34:8443/path?q=1"));
        assertNull(PublicDestinationGuard.blockReason("  http://8.8.8.8/  "));
    }
}
