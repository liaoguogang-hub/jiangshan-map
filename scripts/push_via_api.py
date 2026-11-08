#!/usr/bin/env python3
"""通过 GitHub Git Data API 推送本地提交。

为什么需要它：本机直连 github.com 会被重置，gh-proxy 能拉取但不转发凭据，
`git push` 走不通。而 `gh api`（api.github.com）是通的，于是改用 Git Data API：
   建 blob → 增量建 tree → 建 commit → 更新 ref

用法：
    python scripts/push_via_api.py                # 把 HEAD 推到 origin 对应分支
    python scripts/push_via_api.py --dry-run      # 只看会推哪些文件
"""
import base64
import json
import subprocess
import sys


def run(cmd, stdin=None):
    return subprocess.run(
        cmd, input=stdin, capture_output=True, text=True,
        encoding="utf-8", errors="replace"
    )


def gh(args, payload=None):
    cmd = ["gh", "api"] + args
    if payload is not None:
        cmd += ["--input", "-"]
    p = run(cmd, json.dumps(payload) if payload is not None else None)
    if p.returncode != 0:
        sys.stderr.write(p.stderr)
        sys.exit(1)
    return p.stdout


def gh_json(args, payload=None):
    out = gh(args, payload)
    return json.loads(out) if out.strip() else {}


def repo_slug():
    url = run(["git", "remote", "get-url", "origin"]).stdout.strip()
    # https://github.com/owner/repo.git 或 https://gh-proxy.com/https://github.com/owner/repo.git
    idx = url.find("github.com/")
    if idx < 0:
        sys.exit(f"无法从 origin 解析仓库：{url}")
    path = url[idx + len("github.com/"):].removesuffix(".git").strip("/")
    return path


def main():
    dry = "--dry-run" in sys.argv
    repo = repo_slug()
    branch = run(["git", "rev-parse", "--abbrev-ref", "HEAD"]).stdout.strip()
    if branch in ("", "HEAD"):
        sys.exit("当前处于游离 HEAD，先切到分支")

    remote = gh_json([f"repos/{repo}/branches/{branch}"])
    remote_sha = remote["commit"]["sha"]
    local_sha = run(["git", "rev-parse", "HEAD"]).stdout.strip()

    if remote_sha == local_sha:
        print("远端已是最新，无需推送")
        return

    changed = run(["git", "diff", "--name-only", remote_sha, local_sha]).stdout.split()
    if not changed:
        sys.exit("没有内容差异")

    modes = {}
    for line in run(["git", "ls-tree", "-r", local_sha]).stdout.splitlines():
        meta, path = line.split("\t", 1)
        modes[path] = meta.split()[0]

    print(f"仓库 {repo} · 分支 {branch}")
    print(f"  远端 {remote_sha[:8]} → 本地 {local_sha[:8]}（{len(changed)} 个文件）")
    if dry:
        for p in changed:
            print("   ", p)
        return

    # 1) 逐个建 blob（删除的文件用 sha=null 表示）
    entries = []
    for i, path in enumerate(changed, 1):
        try:
            with open(path, "rb") as f:
                data = f.read()
        except FileNotFoundError:
            entries.append({
                "path": path,
                "mode": modes.get(path, "100644"),
                "type": "blob",
                "sha": None,
            })
            print(f"  [{i}/{len(changed)}] {path} (删除)")
            continue

        blob = gh_json([f"repos/{repo}/git/blobs"], {
            "content": base64.b64encode(data).decode(),
            "encoding": "base64",
        })
        entries.append({
            "path": path,
            "mode": modes.get(path, "100644"),
            "type": "blob",
            "sha": blob["sha"],
        })
        print(f"  [{i}/{len(changed)}] {path} ({len(data)}B)")

    # 2) 以远端 tree 为基底增量构造
    remote_commit = gh_json([f"repos/{repo}/git/commits/{remote_sha}"])
    tree = gh_json([f"repos/{repo}/git/trees"], {
        "base_tree": remote_commit["tree"]["sha"],
        "tree": entries,
    })

    # 3) 用本地 HEAD 的原始提交信息
    message = run(["git", "log", "-1", "--format=%B", "HEAD"]).stdout.strip()
    commit = gh_json([f"repos/{repo}/git/commits"], {
        "message": message,
        "tree": tree["sha"],
        "parents": [remote_sha],
    })

    # 4) 更新分支
    ref = gh_json([f"repos/{repo}/git/refs/heads/{branch}", "--method", "PATCH"], {
        "sha": commit["sha"],
        "force": False,
    })
    new_sha = ref["object"]["sha"]
    print(f"\n已推送：{new_sha[:8]}")

    # 5) 让本地 ref 与远端一致（API 建的 commit 与本地 SHA 不同）
    run(["git", "fetch", "origin", branch])
    run(["git", "reset", "--hard", new_sha])
    print(f"本地已对齐：{new_sha[:8]}")


if __name__ == "__main__":
    main()
