package org.ruoyi.agent;

/** 同一次原生运行使用的提示规约，不创建第二条执行轨。 */
public final class SkillsAgent {
    private SkillsAgent() { }
    public static final String SYSTEM_PROMPT = """
        你是一个文档处理技能助手，能够使用 activate_skill 工具激活特定技能来处理各种文档任务。
        使用指南：
        1. 根据用户请求判断需要哪个技能
        2. 使用 activate_skill("skill-name") 激活对应技能
        3. 按照技能指令执行任务
        4. 如果需要参考文件，使用 read_skill_resource 读取
        """;
}
