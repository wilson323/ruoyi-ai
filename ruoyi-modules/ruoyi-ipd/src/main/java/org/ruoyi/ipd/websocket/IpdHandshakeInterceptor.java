package org.ruoyi.ipd.websocket;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpLogic;
import cn.hutool.crypto.SecureUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.domain.model.LoginUser;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.ruoyi.common.websocket.constant.WebSocketConstants.LOGIN_USER_KEY;

/**
 * IPD WebSocket 握手拦截器（C1：IPD token 替代 platform token）。
 *
 * <p>前端连 ws 时把 IPD token 放在查询参数 {@code ?token=xxx}，本拦截器做三件事：
 * <ol>
 *   <li>用 IPD 独立的 {@link StpLogicJwtForSimple}（loginType="ipd"）从 token 直接拿 token session，
 *       跳过 Sa-Token 全局上下文（WebSocket 握手不经过 MVC 拦截器，全局上下文为空）</li>
 *   <li>从 token session 读 {@code ipdPersonId} + {@code ipdCredentialMarker}，
 *       拿 Person 后比对密码 hash 的 sha256 marker（与 {@link IpdAuthSession#currentPerson()} 同源）</li>
 *   <li>把 Person 装成 LoginUser 塞进 session attributes（{@code loginUser}），
 *       供 {@link org.ruoyi.common.websocket.handler.PlusWebSocketHandler} 无脑读取</li>
 * </ol>
 *
 * <p>约束：
 * <ul>
 *   <li>不引 platform 域的 clientid 校验（IPD 没用客户端设备绑定）</li>
 *   <li>不重复实现 handler：复用 platform 的 {@code PlusWebSocketHandler}，
 *       它从 {@code session.getAttributes().get(LOGIN_USER_KEY)} 取 LoginUser，
 *       并以 {@code LoginUser.userId}（Long）作为 {@code WebSocketSessionHolder} 的 key——person.id 是 Long 直接匹配</li>
 * </ul>
 *
 * @author ruoyi-ipd
 */
@Slf4j
@RequiredArgsConstructor
public class IpdHandshakeInterceptor implements HandshakeInterceptor {

    /** query 参数名：前端握手时携带 IPD token */
    public static final String QUERY_PARAM_TOKEN = "token";

    /** 与 IpdAuthSession 内部 logic 同 loginType，绝不混用 platform 默认域 */
    private static final String IPD_LOGIN_TYPE = IpdAuthSession.LOGIN_TYPE;

    /**
     * token session 内 PersonId 的存储 key（必须与 IpdAuthSession.login()
     * 写入的 {@code "ipdPersonId"} 字面量同源；不动 IpdAuthSession 是为了不污染既有单测断言）。
     */
    private static final String IPD_SESSION_KEY_PERSON_ID = "ipdPersonId";

    /**
     * token session 内凭证 marker 的存储 key（必须与 IpdAuthSession.login()
     * 写入的 {@code "ipdCredentialMarker"} 字面量同源）。
     */
    private static final String IPD_SESSION_KEY_CREDENTIAL_MARKER = "ipdCredentialMarker";

    private final StpLogic ipdLogic = new StpLogicJwtForSimple(IPD_LOGIN_TYPE);

    private final PersonMapper personMapper;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // 1. 从 query 拿 token（前端必须塞，前端改 ipdStompClient 时配套）
        String token = extractQueryToken(request);
        if (token == null || token.isBlank()) {
            log.warn("ipd_websocket_handshake_failed reason=missing_token path={}", request.getURI().getPath());
            return false;
        }

        try {
            // 2. 拿 token session（不依赖 Sa-Token 全局上下文，handshake 时它为空）
            cn.dev33.satoken.session.SaSession saSession = ipdLogic.getTokenSessionByToken(token);
            if (saSession == null) {
                log.warn("ipd_websocket_handshake_failed reason=token_session_null path={}", request.getURI().getPath());
                return false;
            }

            // 3. 读 personId + credentialMarker（与 IpdAuthSession.login() 写入的 key 同源）
            Long personId = saSession.getLong(IPD_SESSION_KEY_PERSON_ID);
            String marker = saSession.getString(IPD_SESSION_KEY_CREDENTIAL_MARKER);
            if (personId == null || marker == null) {
                log.warn("ipd_websocket_handshake_failed reason=session_payload_missing personId={}", personId);
                return false;
            }

            // 4. 拿 Person + 比对 marker（与 IpdAuthSession.currentPerson() 的 hash 校验同源）
            Person person = personMapper.selectById(personId);
            if (person == null || person.getPasswordHash() == null) {
                log.warn("ipd_websocket_handshake_failed reason=person_not_found personId={}", personId);
                return false;
            }
            String currentMarker = SecureUtil.sha256(person.getPasswordHash());
            if (!currentMarker.equals(marker)) {
                log.warn("ipd_websocket_handshake_failed reason=credential_marker_mismatch personId={}", personId);
                return false;
            }

            // 5. 装 LoginUser 塞 attributes
            LoginUser loginUser = new LoginUser();
            loginUser.setUserId(person.getId());
            loginUser.setUsername(person.getUsername());
            loginUser.setUserType(IPD_LOGIN_TYPE);
            attributes.put(LOGIN_USER_KEY, loginUser);

            log.info("ipd_websocket_handshake_ok personId={} username={} path={}",
                personId, person.getUsername(), request.getURI().getPath());
            return true;
        } catch (NotLoginException e) {
            log.warn("ipd_websocket_handshake_failed reason=not_login exceptionType={} path={}",
                e.getClass().getName(), request.getURI().getPath());
            return false;
        } catch (cn.dev33.satoken.exception.SaTokenException e) {
            // 无效/过期 JWT 会抛 SaTokenException（含 NotLoginException 子类之外的变体）；
            // 与 NotLoginException 同为凭证类拒绝，不打 full stack 避免日志噪音
            log.warn("ipd_websocket_handshake_failed reason=invalid_token exceptionType={} path={}",
                e.getClass().getName(), request.getURI().getPath());
            return false;
        } catch (Exception e) {
            log.error("ipd_websocket_handshake_failed reason=unexpected exceptionType={} path={}",
                e.getClass().getName(), request.getURI().getPath(), e);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    /**
     * 从 {@code ServerHttpRequest.getURI().getQuery()} 手撸 token。
     *
     * <p>为什么不用 Spring 的 {@code UriComponentsBuilder}：该类位于
     * {@code spring-web} 而当前模块未引入；且 query 参数只有一个 token，无需全量化解析。
     *
     * <p>URL 解码：处理 token 里如果含 {@code +/=} 时的 base64 padding 场景
     * （Sa-Token JWT 默认不含特殊字符但 tokenName 可包含）。
     */
    private static String extractQueryToken(ServerHttpRequest request) {
        String query = request.getURI().getQuery();
        if (query == null || query.isBlank()) return null;
        for (String param : query.split("&")) {
            int idx = param.indexOf('=');
            if (idx < 0) continue;
            String key = param.substring(0, idx);
            if (!QUERY_PARAM_TOKEN.equals(key)) continue;
            String rawValue = param.substring(idx + 1);
            try {
                return URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return rawValue;
            }
        }
        return null;
    }
}