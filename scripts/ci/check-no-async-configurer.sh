#!/bin/bash
# ============================================================================
# scripts/ci/check-no-async-configurer.sh
# 架构规约门禁:禁止任何类 implements AsyncConfigurer（R174 加固）
# 背景:ApplicationConfig 拆分修复 + 容器内仅保留 Spring Boot 默认 AsyncConfigurer
#      （memory 8559a93f / 951f54ca 复盘:R28.5 已修,但需静态门禁防复发）
# 验收标准:grep "implements AsyncConfigurer" 命中数 = 0
# 适用:IPD 项目全模块（ruoyi-admin / ruoyi-common-* / ruoyi-modules/*）
# ============================================================================
set +e

SCAN_DIRS="ruoyi-admin ruoyi-common ruoyi-modules"
FAIL=0

for d in $SCAN_DIRS; do
    if [ ! -d "$d" ]; then continue; fi
    HITS=$(grep -rln --include="*.java" "implements AsyncConfigurer" "$d/src" 2>&1)
    if [ -n "$HITS" ]; then
        echo "FAIL: $d 命中 implements AsyncConfigurer:"
        echo "$HITS"
        FAIL=1
    else
        echo "PASS: $d 无 implements AsyncConfigurer"
    fi
done

# 应用服务层 @Async 标注必须走 ApplicationConfig 暴露的 taskExecutor（不另开 AsyncConfigurer）
ASYNC_HITS=$(grep -rln --include="*.java" "@Async" ruoyi-modules/ruoyi-ipd/src/main 2>&1 | wc -l)
echo "INFO: ruoyi-modules/ruoyi-ipd @Async 标注文件数 = $ASYNC_HITS"

if [ $FAIL -eq 1 ]; then
    echo ""
    echo "违反架构规约:禁止 implements AsyncConfigurer。"
    echo "如需自定义线程池,在 ApplicationConfig 加 @Bean(name=\"taskExecutor\") 暴露。"
    exit 1
fi

echo ""
echo "全部 PASS"
exit 0