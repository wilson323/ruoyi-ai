#!/usr/bin/env python3
"""Cross-JVM production WorkflowEngine recovery probe. No Maven target writes, no deletes."""
import argparse, configparser, hashlib, json, os, pathlib, shutil, subprocess, tempfile, zipfile
p = argparse.ArgumentParser()
p.add_argument("--jar", required=True)
p.add_argument("--classes", required=True)
p.add_argument("--execute", action="store_true")
p.add_argument("--out", required=True)
a = p.parse_args()
if not a.execute:
    raise SystemExit("Pass --execute for authorized local workflow QA writes.")
root = pathlib.Path("/Users/mac/Documents/ruoyi-ai")
src = pathlib.Path(__file__).with_name("WorkflowEngineRecoveryProbe.java")
jar = pathlib.Path(a.jar).resolve()
classes = pathlib.Path(a.classes).resolve()
config = configparser.ConfigParser(interpolation=None)
config.read(root / ".codex/ipd-dev/config/mysql-client.cnf")
env = os.environ.copy()
env["IPD_PROBE_DB_USER"] = config["client"]["user"].strip("\"'")
env["IPD_PROBE_DB_PASSWORD"] = config["client"]["password"].strip("\"'")
java = pathlib.Path("/Users/mac/tools/jdk-17/Contents/Home/bin")
tmp = pathlib.Path(tempfile.mkdtemp(prefix="ipd-engine-recovery-"))
libs = tmp / "lib"
libs.mkdir()
with zipfile.ZipFile(jar) as z:
    for name in z.namelist():
        if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
            (libs / pathlib.PurePosixPath(name).name).write_bytes(z.read(name))
snapshot = tmp / "aiflow-classes"
shutil.copytree(classes, snapshot)
cp = str(snapshot) + os.pathsep + str(libs / "*")
run = lambda cmd: subprocess.run(cmd, env=env, text=True, capture_output=True)
lombok = next(libs.glob("lombok-*.jar"))
engine = root / "ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WorkflowEngine.java"
overlay = run([str(java / "javac"), "-cp", cp, "-processorpath", str(lombok), "-d", str(snapshot), str(engine)])
if overlay.returncode:
    raise SystemExit("Engine overlay compile failed:\n" + overlay.stderr)
compiled = run([str(java / "javac"), "-cp", cp, "-d", str(tmp), str(src)])
if compiled.returncode:
    raise SystemExit("Probe compilation failed:\n" + compiled.stderr)
runtime = str(tmp) + os.pathsep + cp
result = {
    "scope": "production WorkflowEngine.run/resume plus production WorkflowRuntimeService, WorkflowRuntimeNodeService and JdbcCheckpointSaver; real Start/End nodes; MailSend refused before invocation; definitions remain disabled; no HTTP resume endpoint",
    "adminJarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
    "aiflowClassesSha256": hashlib.sha256(b"".join(path.read_bytes() for path in sorted(snapshot.rglob("WorkflowEngine.class")))).hexdigest(),
    "sourceSha256": hashlib.sha256(src.read_bytes()).hexdigest(),
    "temporaryDirectory": str(tmp),
    "cases": [],
}
def launch(phase, kind, runtime_uuid="-"):
    proc = run([str(java / "java"), "-cp", runtime, "WorkflowEngineRecoveryProbe", phase, kind, runtime_uuid])
    return {"phase": phase, "exitCode": proc.returncode, "stdout": proc.stdout, "stderr": proc.stderr[-4000:]}
for kind in ("replay", "effect", "parallel"):
    case = {"kind": kind, "phases": []}
    result["cases"].append(case)
    failed = launch("fail", kind)
    case["phases"].append(failed)
    pathlib.Path(a.out).write_text(json.dumps(result, ensure_ascii=False, indent=2))
    if failed["exitCode"]:
        raise SystemExit("Probe failed; inspect " + a.out)
    line = [x for x in failed["stdout"].splitlines() if x.startswith("PROBE_RESULT ")][-1]
    runtime_uuid = line.split("runtimeUuid=")[1].split(" ")[0]
    nxt = "verify" if kind == "parallel" else "resume"
    second = launch(nxt, kind, runtime_uuid)
    case["phases"].append(second)
    pathlib.Path(a.out).write_text(json.dumps(result, ensure_ascii=False, indent=2))
    if second["exitCode"]:
        raise SystemExit("Probe second JVM failed; inspect " + a.out)
result["status"] = "PENDING_VALIDATION"
pathlib.Path(a.out).write_text(json.dumps(result, ensure_ascii=False, indent=2))
print(json.dumps({"status": "PROBE_EXIT_0", "evidence": a.out, "threads": [c["phases"][0]["stdout"].split("runtimeUuid=")[1].split(" ")[0] for c in result["cases"]]}, ensure_ascii=False))
