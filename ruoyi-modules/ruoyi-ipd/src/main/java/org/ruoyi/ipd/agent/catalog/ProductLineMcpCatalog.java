package org.ruoyi.ipd.agent.catalog;

import java.util.List;

/**
 * 中国区产线知识库与 FastGPT MCP 的对应表。
 *
 * <p>运行时只认服务标识。{@link #byLineName} 只给标签查找，不决定本次能不能调用。
 * 地址与服务标识保持给定原文，不按相近名称并到另一条。
 */
public final class ProductLineMcpCatalog {

    /** 一个产线知识库端点。 */
    public record Endpoint(String lineName, String serviceId, String url) { }

    private static final List<Endpoint> ENDPOINTS = List.of(
        endpoint("停车设备", "FastGPT-mcp-693fdcafb24e7762a0dc0325",
            "https://zktecowebui.com/api/mcp/app/m1LqPrsCnXiMBYMsLYT9z1Ph/mcp"),
        endpoint("通道设备", "FastGPT-mcp-693fdccdb24e7762a0dc04b5",
            "https://zktecowebui.com/api/mcp/app/tkfuFjmtXZKwssLYfwTlH4RR/mcp"),
        endpoint("万傲瑞达 V6600", "FastGPT-mcp-693fdd09b24e7762a0dc0880",
            "https://zktecowebui.com/api/mcp/app/ysnb1D6Y6Q4CgbHCGW41ORu5/mcp"),
        endpoint("安检设备", "FastGPT-mcp-693fdcbeb24e7762a0dc0422",
            "https://zktecowebui.com/api/mcp/app/oIz7TLlAXLmevvVaGiNZejr1/mcp"),
        endpoint("门禁及梯控设备", "FastGPT-mcp-693fdc63b24e7762a0dbfe86",
            "https://zktecowebui.com/api/mcp/app/lEypuBEUErNIsilRC3trLUp4/mcp"),
        endpoint("考勤", "FastGPT-mcp-693fdceeb24e7762a0dc05de",
            "https://zktecowebui.com/api/mcp/app/pzJMBHDkCpPGXftWNTCiONrr/mcp"),
        endpoint("E-ZKEco Pro", "FastGPT-mcp-693fdcfdb24e7762a0dc07e2",
            "https://zktecowebui.com/api/mcp/app/h0jflTTj96dhTrPYD6vM4DxW/mcp"),
        endpoint("智能视频", "FastGPT-mcp-693fdcdcb24e7762a0dc054b",
            "https://zktecowebui.com/api/mcp/app/ulNLncovFWTACBMTu4FoNDWm/mcp"),
        endpoint("人证核验", "FastGPT-mcp-693fdca1b24e7762a0dc0247",
            "https://zktecowebui.com/api/mcp/app/nEVHopFn8qAyUnj0KEJ2Lbow/mcp"),
        endpoint("消费", "FastGPT-mcp-693fdc8eb24e7762a0dc0169",
            "https://zktecowebui.com/api/mcp/app/ivxTx1xNIXvRNQBCxyUvE5gm/mcp"),
        endpoint("云考勤设备", "FastGPT-mcp-69cce71396a40120630b055e",
            "https://zktecowebui.com/api/mcp/app/ocewMgvFly662Alc3olD2xQn/mcp"),
        endpoint("外协生态", "FastGPT-mcp-69cce77b96a40120630b0c3d",
            "https://zktecowebui.com/api/mcp/app/sE74FoiEBDke63OwT1CgtoWf/mcp"),
        endpoint("ZKTime", "FastGPT-mcp-69cce8b996a40120630b1d80",
            "https://zktecowebui.com/api/mcp/app/rQpQgeMk9EWRj2lRcOhZYiwG/mcp"),
        endpoint("ZKAccess3.5", "FastGPT-mcp-69cce8e096a40120630b1e68",
            "https://zktecowebui.com/api/mcp/app/x689BnYYWim0eaRDRZ2ZRHI4/mcp"),
        endpoint("单机版消费epos", "FastGPT-mcp-69cce8ff96a40120630b1f6d",
            "https://zktecowebui.com/api/mcp/app/pDCDyQOoytoyv2TI443LZSp3/mcp"),
        endpoint("熵基云联", "FastGPT-mcp-69cce93796a40120630b2133",
            "https://zktecowebui.com/api/mcp/app/idm5wczPlaGVCIg4G0gfoxE8/mcp"),
        endpoint("熵基互联", "FastGPT-mcp-69cce91596a40120630b2017",
            "https://zktecowebui.com/api/mcp/app/gd6NWvZuEacyIKnb60MpoSiR/mcp")
    );

    private ProductLineMcpCatalog() {
    }

    /**
     * 全部已登记端点。
     *
     * @return 不可变清单
     */
    public static List<Endpoint> all() {
        return ENDPOINTS;
    }

    /**
     * 按清单标题取端点，只供标签查找。
     *
     * @param lineName 清单标题
     * @return 唯一端点；空名、对不上或重名时为 null
     */
    public static Endpoint byLineName(String lineName) {
        if (lineName == null || lineName.isBlank()) {
            return null;
        }
        String key = lineName.trim();
        Endpoint found = null;
        for (Endpoint endpoint : ENDPOINTS) {
            if (!endpoint.lineName().equals(key)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = endpoint;
        }
        return found;
    }

    /**
     * 按库里记下的服务标识取唯一端点。
     *
     * @param serviceId product_lines.mcp_service_id
     * @return 唯一端点；空值、对不上或重号时为 null
     */
    public static Endpoint byServiceId(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return null;
        }
        String key = serviceId.trim();
        Endpoint found = null;
        for (Endpoint endpoint : ENDPOINTS) {
            if (!endpoint.serviceId().equals(key)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = endpoint;
        }
        return found;
    }

    /**
     * 是否为清单里的服务标识。
     *
     * @param toolId 运行选定的工具编号
     * @return 是服务标识时为 true
     */
    public static boolean isServiceId(String toolId) {
        if (toolId == null) {
            return false;
        }
        for (Endpoint endpoint : ENDPOINTS) {
            if (endpoint.serviceId().equals(toolId)) {
                return true;
            }
        }
        return false;
    }

    private static Endpoint endpoint(String lineName, String serviceId, String url) {
        return new Endpoint(lineName, serviceId, url);
    }
}
