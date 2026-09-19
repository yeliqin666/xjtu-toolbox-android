#!/usr/bin/env python3
"""
检查 release 包里 Gson 读写的模型类有没有被 R8 弄坏。debug 包不开混淆，这类问题只在
release 出现，单测也测不到——#60（第二次打开成绩查询闪退）就是这么漏出去的。

查三件事（直接看 dex，不靠推测）：
  NO_SIGNATURE   List/Map/Set/Pair 等泛型字段丢了泛型签名。Gson 只能把元素读成
                 LinkedTreeMap，用的时候 ClassCastException。
  CLASS_MERGED   模型类被 R8 横向合并（多出 $r8$classId）。Gson 不走构造函数，classId
                 恒为 0，方法会分派到别的类上。
  ABSTRACT_TYPE  字段类型是接口/抽象类（by lazy、sealed、Any…），Gson 无法实例化。

要查哪些类：扫源码里所有 gson.fromJson(..., X::class.java) 和 TypeToken<...>，再顺着
字段类型、泛型参数递归。新加的缓存模型不用改本脚本。

用法（先 ./gradlew :app:assembleRelease）：
    python tools/check_gson_signatures.py
发现问题时退出码为 1。SDK 位置取 local.properties 的 sdk.dir，或环境变量 ANDROID_HOME。
"""
import glob
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java")
APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")
MAPPING = os.path.join(ROOT, "app", "build", "outputs", "mapping", "release", "mapping.txt")
PKG = "com.xjtu.toolbox."

COLLECTIONS = {"java.util.List", "java.util.Map", "java.util.Set", "java.util.Collection"}
LIB_GENERIC = {"kotlin.Pair", "kotlin.Triple", "java.util.Optional"}
LIB_ABSTRACT = {"kotlin.Lazy", "java.lang.Object", "java.lang.Number", "java.lang.CharSequence",
                "java.util.Iterator", "java.lang.Iterable"}


def find_apkanalyzer():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    lp = os.path.join(ROOT, "local.properties")
    if os.path.exists(lp):
        for line in open(lp, encoding="utf-8"):
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
    if not sdk:
        sys.exit("找不到 Android SDK：请在 local.properties 设 sdk.dir 或设环境变量 ANDROID_HOME")
    name = "apkanalyzer.bat" if os.name == "nt" else "apkanalyzer"
    hits = sorted(glob.glob(os.path.join(sdk, "cmdline-tools", "*", "bin", name)))
    if not hits:
        sys.exit(f"SDK 里没有 cmdline-tools/*/bin/{name}，请用 SDK Manager 装 Command-line Tools")
    return hits[-1]


def gson_root_classes():
    """源码里交给 Gson 反序列化的所有类型，解析成全限定名。"""
    decls = {}  # 简单类名 -> 全限定名（嵌套类用 $ 连接）
    targets = set()
    for path in glob.glob(os.path.join(SRC, "**", "*.kt"), recursive=True):
        text = open(path, encoding="utf-8").read()
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        pkg = pkg[1] if pkg else ""
        # 顶层与嵌套类都登记；嵌套类按缩进粗略判断外层类
        outer = None
        # 缩进只认空格/制表符：\s 会跨行吃掉前面的空行，把顶层类误判成嵌套类
        for m in re.finditer(r"^([ \t]*)(?:[\w@]+[ \t]+)*(?:class|object|interface)[ \t]+(\w+)", text, re.M):
            indent, name = m[1], m[2]
            if not indent:
                outer = name
                decls.setdefault(name, f"{pkg}.{name}")
            elif outer:
                decls.setdefault(name, f"{pkg}.{outer}${name}")
        for m in re.finditer(r"fromJson\s*\(", text):
            i, depth = m.end(), 1
            j = i
            while depth and j < len(text):
                depth += {"(": 1, ")": -1}.get(text[j], 0)
                j += 1
            args = text[i:j - 1]
            targets.update(re.findall(r"([\w.]+)(?:<[^>]*>)?::class\.java", args))
            targets.update(re.findall(r"Array<([\w.]+)\??>::class\.java", args))
        for t in re.findall(r"TypeToken<(.+?)>\s*\(\)", text):
            targets.update(re.findall(r"\b([A-Z]\w*)\b", t))
    return sorted({decls[t.split(".")[-1]] for t in targets
                   if t.split(".")[-1] in decls and decls[t.split(".")[-1]].startswith(PKG)})


def main():
    # Windows 控制台默认 GBK，中文输出会乱码
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    if not (os.path.exists(APK) and os.path.exists(MAPPING)):
        sys.exit("没找到 release 产物，先跑 ./gradlew :app:assembleRelease")
    aa = find_apkanalyzer()

    o2n, n2o, field_names = {}, {}, {}
    cur = None
    for line in open(MAPPING, encoding="utf-8"):
        m = re.match(r"^(\S+) -> (\S+):$", line)
        if m:
            cur = m[1]
            o2n[m[1]], n2o[m[2]] = m[2], m[1]
            continue
        m = re.match(r"^\s+(\S+) ([\w$]+) -> (\S+)$", line)
        if m and cur:
            field_names[(cur, m[3])] = m[2]

    dumps = {}

    def dump(orig):
        if orig not in dumps:
            dumps[orig] = subprocess.run([aa, "dex", "code", "--class", o2n.get(orig, orig), APK],
                                         capture_output=True, text=True).stdout
        return dumps[orig]

    def original(desc_cls):
        return n2o.get(desc_cls, desc_cls)

    roots = gson_root_classes()
    seen, missing, issues = set(), [], []
    queue = list(roots)
    while queue:
        orig = queue.pop()
        if orig in seen:
            continue
        seen.add(orig)
        code = dump(orig)
        if ".class" not in code:
            missing.append(orig)  # 已被 R8 整个删掉（没人用），不算问题
            continue
        lines = code.splitlines()
        for i, line in enumerate(lines):
            m = re.match(r"\.field (.*?)(\S+):(\S+)$", line.strip())
            if not m or "static" in m[1]:
                continue
            fname, typ = m[2], m[3]
            where = f"{orig}.{field_names.get((orig, fname), fname)}"
            # 字段后面紧跟的注解块里找泛型签名
            sig_types, has_sig, j = [], False, i + 1
            while j < len(lines) and not lines[j].strip().startswith((".field", ".method", "# ")):
                if "dalvik/annotation/Signature" in lines[j]:
                    has_sig = True
                if has_sig:
                    sig_types += re.findall(r"L([\w/$]+)[;<]", lines[j])
                j += 1
            if "r8$classId" in fname:
                issues.append(("CLASS_MERGED", where))
                continue
            base = typ.lstrip("[")
            if not base.startswith("L"):
                continue
            cls = original(base[1:-1].replace("/", "."))
            if cls in COLLECTIONS or cls in LIB_GENERIC:
                if not has_sig:
                    issues.append(("NO_SIGNATURE", f"{where} : {cls}"))
            elif cls in LIB_ABSTRACT:
                issues.append(("ABSTRACT_TYPE", f"{where} : {cls}"))
            elif cls.startswith(PKG):
                field_code = dump(cls)
                head = field_code.split("\n", 1)[0]
                is_enum = " enum " in head
                if not is_enum and (" interface " in head or " abstract " in head):
                    issues.append(("ABSTRACT_TYPE", f"{where} : {cls}"))
                # 自身带泛型参数的类（枚举的 Enum<X> 签名不算）
                class_part = field_code.split(".field", 1)[0].split(".method", 1)[0]
                if not is_enum and "dalvik/annotation/Signature" in class_part and not has_sig:
                    issues.append(("NO_SIGNATURE", f"{where} : {cls}（泛型类）"))
                queue.append(cls)
            for t in sig_types:
                c = original(t.replace("/", "."))
                if c.startswith(PKG):
                    queue.append(c)

    print(f"源码里交给 Gson 的根类 {len(roots)} 个，连同字段递归共检查 {len(seen)} 个类")
    if missing:
        print("已被 R8 删除（未使用，忽略）：", ", ".join(missing))
    if issues:
        print("发现问题：")
        for kind, where in issues:
            print(f"  [{kind}] {where}")
        sys.exit(1)
    print("未发现问题")


if __name__ == "__main__":
    main()
