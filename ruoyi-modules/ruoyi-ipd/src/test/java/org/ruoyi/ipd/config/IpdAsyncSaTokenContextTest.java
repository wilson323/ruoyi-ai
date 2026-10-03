package org.ruoyi.ipd.config;

import cn.dev33.satoken.context.SaTokenContextForThreadLocalStaff;
import jakarta.servlet.*;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.EnumSet;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IpdAsyncSaTokenContextTest {
    @Test void asyncRegistrationDoesNotReplaceOfficialRequestFilterOrExpandPaths() throws Exception {
        var registration = new IpdWebSecurityConfig(mock(IpdAuthSession.class)).ipdAsyncSaTokenContext();
        var official = new cn.dev33.satoken.spring.SaTokenContextRegister().saTokenContextFilterForServlet();
        assertThat(registration.getFilter()).isNotSameAs(official);
        var servlet = mock(ServletContext.class);
        var dynamic = mock(FilterRegistration.Dynamic.class);
        when(servlet.addFilter("ipdAsyncSaTokenContext", registration.getFilter())).thenReturn(dynamic);
        registration.onStartup(servlet);
        verify(dynamic).addMappingForUrlPatterns(eq(EnumSet.of(DispatcherType.ASYNC)), anyBoolean(), eq("/api/v1/*"));
        verify(dynamic).setAsyncSupported(true);
        var request = new MockHttpServletRequest();
        official.doFilter(request, new MockHttpServletResponse(), (req, res) ->
            assertThat(SaTokenContextForThreadLocalStaff.getModelBoxOrNull()).isNotNull());
        assertThat(SaTokenContextForThreadLocalStaff.getModelBoxOrNull()).isNull();
    }

    @Test void asyncThreadBindsRequestContextAndAlwaysClearsIncludingUnauthorizedFailure() throws Exception {
        var filter = new IpdWebSecurityConfig(mock(IpdAuthSession.class)).ipdAsyncSaTokenContext().getFilter();
        var pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> {
                assertThat(SaTokenContextForThreadLocalStaff.getModelBoxOrNull()).isNull();
                var request = new MockHttpServletRequest(); request.setDispatcherType(DispatcherType.ASYNC);
                try {
                    filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                        assertThat(SaTokenContextForThreadLocalStaff.getModelBoxOrNull()).isNotNull();
                        assertThat(SaTokenContextForThreadLocalStaff.getRequest().getSource()).isSameAs(request);
                        // 原会话校验仍拒绝无token，而不是缺上下文或放行。
                        assertThatThrownBy(() -> new IpdAuthSession(mock(PersonMapper.class)).currentPerson())
                            .isInstanceOf(cn.dev33.satoken.exception.NotLoginException.class);
                        throw new ServletException("fixture failure");
                    });
                    throw new AssertionError("expected chain failure");
                } catch (ServletException expected) { assertThat(expected).hasMessage("fixture failure"); }
                catch (java.io.IOException unexpected) { throw new AssertionError(unexpected); }
                assertThat(SaTokenContextForThreadLocalStaff.getModelBoxOrNull()).isNull();
            }).get();
            assertThat(SaTokenContextForThreadLocalStaff.getModelBoxOrNull()).isNull();
        } finally { pool.shutdownNow(); }
    }
}
