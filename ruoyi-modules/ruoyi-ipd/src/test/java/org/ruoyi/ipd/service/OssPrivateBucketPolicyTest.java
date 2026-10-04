package org.ruoyi.ipd.service;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.oss.core.OssClient;
import org.ruoyi.common.oss.enums.AccessPolicyType;
import org.ruoyi.common.oss.properties.OssProperties;
import org.ruoyi.common.oss.exception.OssException;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
@Tag("dev")
class OssPrivateBucketPolicyTest {
    OssClient storage(S3AsyncClient s3, AccessPolicyType type) throws Exception {
        OssClient storage = mock(OssClient.class, CALLS_REAL_METHODS);
        doReturn(type).when(storage).getAccessPolicy();
        OssProperties properties = new OssProperties(); properties.setBucketName("private-test");
        Field p = OssClient.class.getDeclaredField("properties"); p.setAccessible(true); p.set(storage,properties);
        Field c = OssClient.class.getDeclaredField("client"); c.setAccessible(true); c.set(storage,s3);
        return storage;
    }
    void noPolicy(S3AsyncClient s3) {
        when(s3.getBucketPolicy(any(Consumer.class))).thenReturn(CompletableFuture.failedFuture(S3Exception.builder().statusCode(404)
          .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucketPolicy").build()).build()));
    }
    @Test void configPublicAlwaysRejected() throws Exception {
        S3AsyncClient s3=mock(S3AsyncClient.class); assertThatThrownBy(() -> storage(s3,AccessPolicyType.PUBLIC).assertPrivateBucket()).isInstanceOf(OssException.class); verifyNoInteractions(s3);
    }
    @Test void privateFlagCannotHideActualPublicPolicy() throws Exception {
        S3AsyncClient s3=mock(S3AsyncClient.class);
        when(s3.getBucketPolicy(any(Consumer.class))).thenReturn(CompletableFuture.completedFuture(GetBucketPolicyResponse.builder().policy("{public:true}").build()));
        assertThatThrownBy(() -> storage(s3,AccessPolicyType.PRIVATE).assertPrivateBucket()).isInstanceOf(OssException.class);
    }
    @Test void publicAclAndUnknownPolicyReadBothRejected() throws Exception {
        S3AsyncClient s3=mock(S3AsyncClient.class); noPolicy(s3);
        when(s3.getBucketAcl(any(Consumer.class))).thenReturn(CompletableFuture.completedFuture(GetBucketAclResponse.builder().grants(Grant.builder().grantee(Grantee.builder().type(Type.GROUP).uri("http://acs.amazonaws.com/groups/global/AllUsers").build()).permission(Permission.READ).build()).build()));
        assertThatThrownBy(() -> storage(s3,AccessPolicyType.PRIVATE).assertPrivateBucket()).isInstanceOf(OssException.class);
        when(s3.getBucketPolicy(any(Consumer.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("unavailable")));
        assertThatThrownBy(() -> storage(s3,AccessPolicyType.PRIVATE).assertPrivateBucket()).isInstanceOf(OssException.class);
    }
    @Test void confirmedPrivateBucketPasses() throws Exception {
        S3AsyncClient s3=mock(S3AsyncClient.class); noPolicy(s3);
        when(s3.getBucketAcl(any(Consumer.class))).thenReturn(CompletableFuture.completedFuture(GetBucketAclResponse.builder().grants(Grant.builder().grantee(Grantee.builder().type(Type.CANONICAL_USER).id("owner").build()).permission(Permission.FULL_CONTROL).build()).build()));
        assertThatCode(() -> storage(s3,AccessPolicyType.PRIVATE).assertPrivateBucket()).doesNotThrowAnyException();
    }
}
