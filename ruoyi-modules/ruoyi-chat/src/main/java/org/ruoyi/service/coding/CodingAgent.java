package org.ruoyi.service.coding;

/** Compatibility contract for coding callers; native execution is owned by CodingServiceImpl. */
public interface CodingAgent {
    String chat(String userMessage);
}
