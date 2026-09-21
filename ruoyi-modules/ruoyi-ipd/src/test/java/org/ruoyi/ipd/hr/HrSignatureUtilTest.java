package org.ruoyi.ipd.hr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HrSignatureUtil 单测（FA-HR-Sync·2026-09-21 R149，@Tag dev）。
 *
 * <p>对照 HR 接口文档 §1.2.3 案例代码的预期输出，验证三件事：
 * <ol>
 *   <li>参数按 ASCII 升序拼接，空值/serialVersionUID 跳过，data JSON 化</li>
 *   <li>secretKey 末尾追加</li>
 *   <li>MD5 → 十六进制大写、去前导 0、与文档示例一致</li>
 * </ol>
 *
 * <p>文档示例：paramsTokenData = appId + format + method + timestamp + signType，secretKey=3，
 * 预期签名 74B5DB3238B324C1A9EA99D94EE22934（见 §1.3.6 入参示例配套）。
 * <p>另：拼装结果含 signature 行 {@code 18B6357C3A4C21AA809DCB19AEED67E3} 对应 token.get，
 * 见 §1.3.8。
 */
@Tag("dev")
@DisplayName("FA-HR-Sync-2 HrSignatureUtil: HR 接口文档示例密钥签名对照")
class HrSignatureUtilTest {

    private final HrSignatureUtil util = new HrSignatureUtil();

    @Test
    @DisplayName("文档 §1.3.6 示例入参签名 = 74B5DB3238B324C1A9EA99D94EE22934")
    void signMatchesDocExample_tokenGet() {
        // 文档 §1.3.6 入参示例
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("appId", "123");
        params.put("format", "JSON");
        params.put("method", "zkteco.token.get");
        params.put("sign", "PLACEHOLDER_WILL_BE_REPLACED"); // 模拟已有 sign 被滤掉
        params.put("signType", "md5");
        params.put("timestamp", 1660182460583L);

        String sign = util.sign(params, "3");
        // 文档样例：拼装 → appId=123&format=JSON&method=zkteco.token.get&signType=md5&timestamp=1660182460583&secretKey=3
        // 文档 §1.3.6 入参示例里的 sign=74B5DB3238B324C1A9EA99D94EE22934 即对应该拼装的 MD5
        assertThat(sign).isEqualTo("74B5DB3238B324C1A9EA99D94EE22934");
    }

    @Test
    @DisplayName("拼装字符串按 ASCII 升序：appId < format < method < signType < timestamp")
    void buildSignString_isSortedByKey() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("timestamp", "4");
        params.put("signType", "md5");
        params.put("method", "get");
        params.put("format", "JSON");
        params.put("appId", "1");

        String src = util.buildSignString(params, "secretKey=3");
        // 注意：secretKey=3 会被再次 append -> "appId=1&format=JSON&method=get&signType=md5&timestamp=4&secretKey=3"
        // 但我们的 nvl(secretKey) 拿到的是 "3"，所以最终拼装是 "...&timestamp=4&secretKey=3"
        assertThat(src).isEqualTo("appId=1&format=JSON&method=get&signType=md5&timestamp=4&secretKey=3");
    }

    @Test
    @DisplayName("空值与 serialVersionUID 被跳过，data 字段 JSON 化")
    void buildSignString_skipsEmptyAndSerializesData() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("appId", "x");
        params.put("empty", "");               // 空字符串跳过
        params.put("blank", "   ".trim());     // 留作 trim 行为验证（trim 后空视为空）
        params.put("nullVal", null);           // null 跳过
        params.put("serialVersionUID", "1L");   // 反序列化残留，文档示例显式过滤
        Map<String, Object> data = Map.of("DATA", Map.of("HEAD", Map.of("INTF_ID", "HR008")));
        params.put("data", data);
        params.put("method", "zkteco.ehr.getUserInfo");

        String src = util.buildSignString(params, "k");
        // 拼装顺序：appId < data < method（ASCII d < m）
        assertThat(src)
            .startsWith("appId=x&data=")
            .contains("method=zkteco.ehr.getUserInfo")
            .endsWith("&secretKey=k");
        // 验证 data 字段确实是 JSON 形式
        assertThat(src).contains("\"DATA\":\"{");
    }

    @Test
    @DisplayName("MD5 十六进制大写且去前导 0：std md5('abc')")
    void md5Hex_matchesStandardMd5() {
        // 标准 md5("abc") = 900150983CD24FB0D6963F7D28E17F72（hex 大写）
        assertThat(util.md5Hex("abc")).isEqualTo("900150983CD24FB0D6963F7D28E17F72");
    }

    @Test
    @DisplayName("MD5 字节前置 0 自动丢失（与 HR 文档 BigInteger(1,).toString(16) 行为一致）")
    void md5Hex_dropsLeadingZeros() {
        // 标准 32 字符大写十六进制 = 8f...，但 HR 实现走 BigInteger(1,).toString(16)，
        // 字节首位 < 0x10 时首字符自动消失。
        // 这里用 md5("a") = 0CC175B9C0F1B6A831C399E269772661，HR 实现应 = CC175B9C0F1B6A831C399E269772661
        assertThat(util.md5Hex("a")).isEqualTo("CC175B9C0F1B6A831C399E269772661");
    }
}
