# G 独立 current reactor 验证

裁决：PARTIAL。原 Launcher exit 1；26 suite 实际发现/执行160项，159通过、1失败、0跳过、0中止、0容器失败。唯一失败 cancelWithoutLocalHandleStaysRequested 期待 CANCEL_REQUESTED 实为 CANCELLED，须核无owner旧夹具与新增 detached cancellation 合同，不能直接改期待掩盖。

CP含26个当前 reactor classes、当前 IPD test-classes和本 Launcher；无旧ruoyi JAR/其他临时classes。26测试class SHA逐项与preflight相符。JUnit Jupiter5.12.2/Platform1.12.2；不含Vintage，不能宣称JUnit4覆盖。过时 preparation.json 不作为终态证据。

原日志有真实异步记忆/会话镜像失败。OfficialCapabilitiesAcceptanceTest证明真实Docker同步文件写读、Shell、host canary隔离和容器关闭；未断言异步memory/session mirror落盘成功。SDK 0 active request/0 transport shutdown不覆盖这些失败。因此不能称全官方后台持久能力完成。详情及原路径/hash/逐suite在同名JSON。

本轮仅只读验证及证据写入，未重跑、未改main/target/index/运行态。
