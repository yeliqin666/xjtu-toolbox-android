"""Compare repaired and original production code with identical regression tests."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

BASELINE = "69e6a68fce7621d9d310d80e0fa80abbf1914e1f"
ROOT = Path(__file__).resolve().parents[2]
REPORTS = ROOT / "storage-regression-reports"

# Gradle task, module, test source, class, expected original assertion markers.
GROUPS = [
    (
        ":app:testDebugUnitTest", "app",
        "app/src/test/java/com/xjtu/toolbox/data/DataCacheConcurrencyTest.kt",
        "com.xjtu.toolbox.data.DataCacheConcurrencyTest",
        {"allInstancesAndClearUseTheDirectoryLock": "S1_SHARED_LOCK",
         "failedReplacementDoesNotOverwriteTargetOrLeaveTempFile": "S1_TEMP_LEAK"},
    ),
    (
        ":shared:data:jvmTest", "shared/data",
        "shared/data/src/jvmTest/kotlin/com/xjtu/toolbox/platform/SharedJvmFileStoreTest.kt",
        "com.xjtu.toolbox.platform.SharedJvmFileStoreTest",
        {"ordinaryAndSecurePreserveWrites": "S2_LOST_UPDATE",
         "wipeClearsOldHandles": "S2_STALE_HANDLE",
         "differentRootsRemainIsolated": "S2_ROOT_ALIAS",
         "corruptFileCanBeExplicitlyWiped": "S2_CORRUPT_READ"},
    ),
    (
        ":shared:model:jvmTest", "shared/model",
        "shared/model/src/jvmTest/kotlin/com/xjtu/toolbox/platform/KeyValueStoreJvmTest.kt",
        "com.xjtu.toolbox.platform.KeyValueStoreJvmTest",
        {"failedReplacementKeepsCommittedMemory": "S2_UNCOMMITTED_MEMORY"},
    ),
]


def validate_results(results, classname, expected, original, status, label):
    """Never accept build errors, skipped/missing tests or unrelated failures."""
    cases = {}
    for xml in results.glob("TEST-*.xml"):
        for case in ET.parse(xml).iter("testcase"):
            name = case.get("name", "").removesuffix("()")
            if case.get("classname") != classname or name not in expected or name in cases:
                raise AssertionError(f"{label}: unexpected/duplicate test {case.attrib}")
            cases[name] = case
    if set(cases) != set(expected):
        raise AssertionError(f"{label}: missing tests {set(expected) - set(cases)}")
    for name, case in cases.items():
        failures = case.findall("failure")
        if case.find("skipped") is not None or case.find("error") is not None:
            raise AssertionError(f"{label}: {name} skipped or errored")
        if original:
            marker = expected[name]
            if len(failures) != 1:
                raise AssertionError(f"{label}: {name} did not reproduce its defect")
            failure = failures[0]
            message = failure.get("message", "")
            kind = failure.get("type", "")
            if marker not in message or not ("AssertionError" in kind or "AssertionFailedError" in kind):
                raise AssertionError(f"{label}: {name} failed for the wrong reason: {kind}: {message}")
            print(f"REPRODUCED {marker}: {name}", flush=True)
        else:
            if failures:
                raise AssertionError(f"{label}: {name} failed after repair")
            print(f"PASS {name}", flush=True)
    if (status == 0) == original:
        raise AssertionError(f"{label}: unexpected Gradle exit {status}")


def check_group(root, group, original):
    task, module, _, classname, expected = group
    results = root / module / "build/test-results" / task.rsplit(":", 1)[-1]
    # Never accept reports from another invocation.
    if results.exists():
        shutil.rmtree(results)
    cmd = ["bash", "gradlew", task, "--no-build-cache"]
    for method in expected:
        cmd.extend(["--tests", f"{classname}.{method}"])
    label = ("original" if original else "repaired") + "-" + module.replace("/", "-")
    print(f"::group::{label}", flush=True)
    status = subprocess.run(cmd, cwd=root).returncode
    print("::endgroup::", flush=True)
    report = REPORTS / label
    report.mkdir(parents=True, exist_ok=True)
    (report / "gradle-exit.txt").write_text(str(status), encoding="utf-8")
    if results.is_dir():
        shutil.copytree(results, report / "xml", dirs_exist_ok=True)
    validate_results(results, classname, expected, original, status, label)


def main():
    REPORTS.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="storage-baseline-") as tmp:
        old = Path(tmp) / "original"
        subprocess.run(["git", "worktree", "add", "--detach", str(old), BASELINE],
                       cwd=ROOT, check=True)
        try:
            for group in GROUPS:
                source = group[2]
                dest = old / source
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / source, dest)
            for group in GROUPS:
                check_group(ROOT, group, original=False)
                check_group(old, group, original=True)
        finally:
            subprocess.run(["git", "worktree", "remove", "--force", str(old)],
                           cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
