# 完成门安全原因分类

裁决：根因未定位，分类补强待验证。

六行缺口：仓库ruoyi-ai；入口完成门；边界仅Gate/Test，Kernel由root接线；沿用STEP/failed不新增事件；判据保持原样，仅提供固定枚举；A级源码及实际SOURCE/hash，完整basis未存，不推定0.74为根因。

before SHA-256：
- ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/service/ProjectAgentCompletionGate.java: d0dc20beb85534c9a56c56df6c636b7041f1bf837266f5ac9bc2488b10b8a404
- ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/service/ProjectAgentCompletionGateTest.java: 911a9cc35420c2bc3870fcf6bb41db3c6b6eafe691bc6cb6b814566e61a0d091

源码合同：首拒绝顺序固定五类，reject仍返回COMPLETION_REJECTED。完整citationText在RunHandle先交noteSource再移除；公开仅17201长度/hash与1000preview，故本次run实际触发分支未定位，不能以preview缺0.74放宽门。

隔离javac目录：/var/folders/8l/6tnv8ssd2vs0h8463rnv66b00000gn/T/ipd-b-gate-reasons-vz9oki9o；compile=0；probe=0；PASS cases=7
。未运行Maven；JUnit待root定向。

after ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/service/ProjectAgentCompletionGate.java SHA256 b8a931e9d9b248a0cf874e92fb95212d8773d97697dcea9167a7c93a023bdbe6

after ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/service/ProjectAgentCompletionGateTest.java SHA256 4e51ba6d9f16e035a9c35043f49de10a3528e3ae06ad10fc0d89547ff2b530c9
