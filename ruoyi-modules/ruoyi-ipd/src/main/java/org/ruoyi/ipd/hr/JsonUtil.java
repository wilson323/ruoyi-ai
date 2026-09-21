package org.ruoyi.ipd.hr;

import cn.hutool.json.JSONUtil;

/** 内部 JSON 序列化辅助（包装 Hutool JSONUtil）。 */
final class JsonUtil {
    private JsonUtil() { }
    static String toJsonString(Object o) {
        return JSONUtil.toJsonStr(o);
    }
}
