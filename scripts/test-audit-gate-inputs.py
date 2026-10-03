#!/usr/bin/env python3
"""独立临时夹具验证门禁读取/计数/全量扫描；不连接数据库或构建工程。

本文件是「门禁自身的自证」：对每个门禁同时验证**干净输入必须放行**（EXIT=0）
与**真违规必须拦下**（EXIT≠0）。只验「能红」不验「不乱红」时，门禁可以在正确
代码上大面积误报而无人察觉——2026-10-03 实测 check-entity-complete.sh 报 61 处
「Service 无 Controller」而真缺口为 0、check-tenant-excludes-apply.sh 报 74 处
「重叠」而其中真信号为 0，都是这个形状。

前端两个门禁（check-a11y-basics.sh / check-memory-leak-pattern.sh）已于
2026-10-03 迁至前端仓 ruoyi-ipd-web（提交 e35fe28），本仓副本随 1bdd505c 删除；
它们的同类自证在前端仓自己的测试里，本文件不再覆盖，只保留「副本不得回流」的断言。
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]


class GateInputsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="ipd-gate-fixture-")
        self.root = Path(self.tmp.name)
        self.front = self.root / "front/src"
        self.write("front/src/example.ts", "export const example = 1;\n")
        for tree in ("ruoyi-modules/ruoyi-ipd/src/main/java", "ruoyi-admin", "ruoyi-common"):
            self.write(tree + "/Example.java", "public class Example {}\n")
        self.tests = self.root / "ruoyi-modules/ruoyi-ipd/src/test/java"
        self.write(str(self.tests.relative_to(self.root)) + "/ExampleTest.java", '@Tag("dev") class ExampleTest {}\n')
        self.write("pom.xml", "<project><groups>dev</groups></project>\n")
        self.write("ruoyi-modules/ruoyi-ipd/src/main/resources/mapper.xml", "<mapper/>\n")
        self.write("front/src/locales/en.json", '{"example": "Example"}\n')
        self.write("docs/script/sql/schema.sql", "SELECT 1;\n")
        self.write("docs/ipd-系统说明/开发计划-看板镜像.md", "# 计划\n")
        # 判定器按 "$REPO/scripts/lib/<name>" 定位（如 check-doc-code-sync.sh 的
        # CHECKER="$REPO/scripts/lib/java-param-doc-check.py"）。夹具必须带一份，
        # 否则判定器缺失会被门禁正确地判成「门禁失效」（EXIT=2）——那是门禁在尽责，
        # 不是夹具该得到的结果。
        lib_dst = self.root / "scripts/lib"
        lib_dst.mkdir(parents=True, exist_ok=True)
        for src in (REPO / "scripts/lib").glob("*.py"):
            shutil.copy(src, lib_dst / src.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, content):
        p = self.root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)
        return p

    def run_gate(self, name, **extra):
        env = dict(os.environ, REPO=str(self.root), FRONT_DIR=str(self.front),
                   TEST_BASE=str(self.root / "ruoyi-modules"), POM_FILE=str(self.root / "pom.xml"),
                   BACKEND_ROOT=str(self.root), FRONTEND_ROOT=str(self.root / "front"),
                   R_REPORTS_DIR=str(self.root / "reports"), REPORT_OUT=str(self.root / "result.md"))
        env.update(extra)
        r = subprocess.run(["bash", str(REPO / "scripts" / name)], env=env,
                           cwd=self.root, text=True, capture_output=True)
        self.last_output = r.stdout + r.stderr
        return r.returncode

    def test_count_zero_is_single_zero_and_read_error_is_nonzero(self):
        helper = REPO / "scripts/lib/audit-gate-input.sh"
        source = self.write("zero.txt", "unrelated\n")
        result = subprocess.run(["bash", "-c", 'source "$1"; gate_grep -c missing "$2"',
                                 "fixture", str(helper), str(source)], capture_output=True, text=True)
        self.assertEqual((result.returncode, result.stdout), (0, "0\n"))
        result = subprocess.run(["bash", "-c", 'source "$1"; gate_grep -c missing "$2"',
                                 "fixture", str(helper), str(self.root / "absent")], capture_output=True)
        self.assertNotEqual(result.returncode, 0)

    def test_front_gates_moved_out_of_this_repository_and_must_not_flow_back(self):
        """前端两门禁已迁 ruoyi-ipd-web（e35fe28）。

        原先这两个用例在本仓跑它们，迁移后本仓已无脚本可跑，用例必然 127 报错——
        红的原因不是门禁有问题，是用例还在测不存在的东西。这里保留一条反向断言：
        副本一旦回流本仓即失败（否则会出现「两仓各有一份、只跑其中一份」的分叉）。
        它们完整的行为自证（干净输入放行 / 违规拦下 / 注释与测试物料不误报）随脚本
        迁至前端仓，应在该仓的测试里维护，不在本文件重建。
        """
        for name in ("check-a11y-basics.sh", "check-memory-leak-pattern.sh"):
            with self.subTest(name=name):
                self.assertFalse(
                    (REPO / "scripts" / name).exists(),
                    f"{name} 已于 2026-10-03 迁至前端仓 ruoyi-ipd-web，本仓不应再出现副本",
                )

    def test_source_gates_have_real_nonempty_inputs_and_late_violations(self):
        self.assertEqual(self.run_gate("check-doc-code-sync.sh"), 0, self.last_output)
        self.assertEqual(self.run_gate("check-naming-convention.sh"), 0, self.last_output)
        for name in ("check-doc-code-sync.sh", "check-naming-convention.sh"):
            self.assertNotEqual(self.run_gate(name, REPO=str(self.root / "absent")), 0)
        for n in range(120):
            self.write(f"ruoyi-modules/ruoyi-ipd/src/main/java/Clean{n:03}.java", f"public class Clean{n} {{}}\n")
        self.write("ruoyi-modules/ruoyi-ipd/src/main/java/zz.java", "public class bad_class {}\n")
        self.assertEqual(self.run_gate("check-naming-convention.sh"), 1, self.last_output)
        # 规则 4 的唯一判据是「写了 @param 但名字与签名对不上」。原先这里只写「方法没
        # 有 javadoc」——那属于规则 3「缺注释」，已按 owner 2026-10-03 决策降为 INFO、
        # 不计违规（见脚本头部第 13-14 行与判定器 java-param-doc-check.py 的口径说明）。
        # 用例必须喂规则 4 认得的形态，否则测的是「脚本对我们以为的违规不报红」这个假命题。
        self.write("ruoyi-modules/ruoyi-ipd/src/main/java/BadService.java",
                   "public class BadService {\n"
                   "    /**\n"
                   "     * 做点事。\n"
                   "     * @param wrongName 这个名字在签名里不存在\n"
                   "     */\n"
                   "    public String method(String rightName) { return null; }\n"
                   "}\n")
        self.assertEqual(self.run_gate("check-doc-code-sync.sh"), 1, self.last_output)

    def test_java_gates_clean_missing_seed_and_actual_violation(self):
        specs = [("surefire-groups-coverage", "SGC", "class MissingTagTest {}\n"),
                 ("test-assertion-history", "TAH", 'assertTrue("现状保留");\n'),
                 ("mock-data-realism", "MDR", "when(foo).thenReturn(99999);\n"),
                 ("assertion-line-drift", "ALD", "assertEquals(foo, bar, 999);\n")]
        for name, seed, bad in specs:
            with self.subTest(name=name):
                file = self.tests / "ExampleTest.java"
                file.write_text('@Tag("dev") class ExampleTest {}\n')
                # Existing repository supplies read-only git history; input remains the fixture.
                args = {"REPO": str(REPO)} if seed == "TAH" else {}
                self.assertEqual(self.run_gate(f"check-{name}.sh", **args), 0, self.last_output)
                self.assertNotEqual(self.run_gate(f"check-{name}.sh", TEST_BASE=str(self.root / "absent"), **args), 0)
                self.assertNotEqual(self.run_gate(f"check-{name}.sh", **{seed + "_FAIL_SEED": "1"}, **args), 0)
                file.write_text(bad)
                self.assertNotEqual(self.run_gate(f"check-{name}.sh", **args), 0, self.last_output)

    def test_declaration_comments_and_cross_line_calls_are_not_violations(self):
        self.write("ruoyi-modules/ruoyi-ipd/src/main/java/Helper.java",
                   "/** class accessor refers to syntax, not a declaration. */\npublic final class Helper {}\n")
        self.assertEqual(self.run_gate("check-naming-convention.sh"), 0, self.last_output)
        (self.tests / "ExampleTest.java").write_text(
            "assertTrue(flag);\nObject value = config(null, 0, 999);\n")
        self.assertEqual(self.run_gate("check-assertion-line-drift.sh"), 0, self.last_output)

    def test_deletion_requires_inputs_and_returns_red_on_reference(self):
        script = REPO / "scripts/check-deletion-consistency.sh"
        env = dict(os.environ, BACKEND_ROOT=str(self.root), FRONTEND_ROOT=str(self.root / "front"))
        def call():
            return subprocess.run(["bash", str(script), "DeletedService"], env=env,
                                  capture_output=True, text=True)
        self.assertEqual(call().returncode, 0)
        self.write("ruoyi-modules/ruoyi-ipd/src/main/java/Reference.java", "class Reference { DeletedService service; }\n")
        result = call(); self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        (self.root / "front/src/locales/en.json").unlink()
        self.assertNotEqual(call().returncode, 0)

    def test_report_claims_missing_clean_and_real_mismatch(self):
        name = "fix-r-report-line-claims.sh"
        self.assertNotEqual(self.run_gate(name), 0)
        self.write("reports/R83-example.md", "# 文档\n普通无匹配\n")
        self.assertEqual(self.run_gate(name), 0, self.last_output)
        self.write("reports/R83-example.md", "**报告状态**：完成（实测 1 行）\n")
        self.assertEqual(self.run_gate(name), 0, self.last_output)
        self.write("reports/R83-example.md", "**报告状态**：完成（实测 99 行）\n")
        self.assertEqual(self.run_gate(name), 1, self.last_output)

    def test_doc_db_drift_reads_complete_inputs_with_fake_readonly_schema(self):
        for scope in ("开发说明", "ipd-系统说明"):
            for n in range(5):
                self.write(f"docs/{scope}/doc{n}.md", "# 正常文档\n")
        config = self.write("mysql.cnf", "[client]\n")
        fake = self.write("bin/mysql", "#!/usr/bin/env bash\nfor i in 1 2 3 4 5 6 7 8 9 10; do echo table_$i; done\n")
        fake.chmod(0o755)
        args = dict(REPO_ROOT=str(self.root), MYSQL_CNF=str(config),
                    PATH=str(fake.parent) + os.pathsep + os.environ["PATH"])
        self.assertEqual(self.run_gate("check-doc-db-drift.sh", **args), 0, self.last_output)
        self.write("docs/ipd-系统说明/doc4.md", "```sql\nSELECT * FROM certainly_missing_table;\n```\n")
        self.assertNotEqual(self.run_gate("check-doc-db-drift.sh", **args), 0, self.last_output)
        self.assertNotEqual(self.run_gate("check-doc-db-drift.sh", **dict(args, REPO_ROOT=str(self.root / "absent"))), 0)

    def test_workflow_calls_only_existing_gate_names(self):
        workflow = (REPO / ".github/workflows/r25-root-cause-lint.yml").read_text()
        paths = re.findall(r'bash (scripts/[\w-]+\.sh)', workflow)
        self.assertGreaterEqual(len(paths), 10)
        for name in paths:
            self.assertTrue((REPO / name).is_file(), name)

    def _gate_count(self, snippet, *files):
        """在子 bash 里跑片段，返回 (退出码, stdout)。"""
        helper = REPO / "scripts/lib/audit-gate-input.sh"
        result = subprocess.run(
            ["bash", "-c", 'source "$1"; ' + snippet, "fixture", str(helper), *files],
            capture_output=True, text=True)
        return result.returncode, result.stdout

    def test_gate_count_lines_real_zero_empty_and_read_failure(self):
        """gate_count_lines：真实行数 / 空文件=0 / 读取失败=exit 2，绝不返回空串。"""
        three = self.write("three.txt", "a\nb\nc\n")
        empty = self.write("empty-count.txt", "")
        absent = self.root / "absent-count.txt"

        # 真实行数
        rc, out = self._gate_count('gate_count_lines "$2"', str(three))
        self.assertEqual((rc, out), (0, "3"), out)
        # 空文件 = 合法 0（不是错误）
        rc, out = self._gate_count('gate_count_lines "$2"', str(empty))
        self.assertEqual((rc, out), (0, "0"), out)
        # 文件缺失 = 门禁错误 exit 2，且 stdout 不得吐出任何可被当成计数的值
        rc, out = self._gate_count('gate_count_lines "$2"', str(absent))
        self.assertEqual(rc, 2)
        self.assertNotRegex(out.strip(), r"\d", f"失败时仍吐出了数字: {out!r}")
        # 目录当输入 = 同样报错
        rc, out = self._gate_count('gate_count_lines "$2"', str(self.root))
        self.assertEqual(rc, 2)
        # 调用方模式：失败必须能中断（不静默继续）
        rc, out = self._gate_count(
            'v=$(gate_count_lines "$2") || exit 2; echo "got[$v]"', str(absent))
        self.assertEqual((rc, out), (2, ""), out)

    def test_dynamic_loadable_count_never_degrades_to_empty_string(self):
        """check-dynamic-loadable.sh 不得再出现 `|| echo 0` 计数降级写法，且必须真的用 gate_count_lines。"""
        script = (REPO / "scripts/check-dynamic-loadable.sh").read_text()
        # 旧缺陷写法：`wc -l < f 2>/dev/null | tr -d ' ' || echo 0`
        self.assertNotRegex(script, r"wc -l < .*\|\| echo 0", "计数降级写法仍在")
        # 5 处计数都改走公共库，不另造模式
        self.assertGreaterEqual(script.count("gate_count_lines"), 5)
        self.assertIn("audit-gate-input.sh", script)
        # fe_glob_raw.txt 必须在 ACCESS_FILE 判空分支之前无条件创建，
        # 否则前端缺失时计数文件根本不存在（曾经触发空串计数的同款根因）
        lines = script.splitlines()
        create_idx = [i for i, l in enumerate(lines) if l.strip() == '> "$TMPDIR_CHECK/fe_glob_raw.txt"']
        guard_idx = next(i for i, l in enumerate(lines) if l.startswith('if [ -n "$ACCESS_FILE" ]'))
        self.assertTrue(create_idx, "缺少 fe_glob_raw.txt 的无条件创建")
        self.assertLess(min(create_idx), guard_idx, "无条件创建必须排在 ACCESS_FILE 判空之前")

    def test_dynamic_loadable_propagates_count_failure_as_exit_2(self):
        """把公共库换成总是失败的桩，端到端证明脚本以 exit 2 中止，而不是继续出报告。"""
        script_path = REPO / "scripts/check-dynamic-loadable.sh"
        lib_path = REPO / "scripts/lib/audit-gate-input.sh"
        lib_backup = lib_path.read_text()
        stub = self.write("stub-lib.sh", "gate_count_lines() { echo '[gate] 注入失败' >&2; return 2; }\n")
        try:
            lib_path.write_text(stub.read_text())
            r = subprocess.run(
                ["bash", str(script_path), "--output", str(self.root / "out.json")],
                env=dict(os.environ, BACKEND_ROOT=str(self.root)),
                capture_output=True, text=True, timeout=90)
            self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
            self.assertIn("注入失败", r.stderr)
            # 失败时不得已经写出报告（结论方向不可信，不能留下看似正常的产物）
            self.assertFalse((self.root / "out.json").exists())
        finally:
            lib_path.write_text(lib_backup)
        self.assertEqual(lib_path.read_text(), lib_backup, "公共库未还原")


if __name__ == "__main__":
    unittest.main(verbosity=2)
