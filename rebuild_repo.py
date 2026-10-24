import base64
import json
import os
import subprocess
import urllib.request

TOKEN = subprocess.check_output(["gh", "auth", "token"], shell=True).decode().strip()
BASE = "https://api.github.com/repos/138849070/haobo-cleaner"


def api(method, path, data=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Content-Type", "application/json")
    body = json.dumps(data).encode() if data else None
    with urllib.request.urlopen(req, body, timeout=60) as resp:
        return json.loads(resp.read())


SKIP_DIRS = {".git", "build", ".gradle", ".idea"}
SKIP_PREFIX = ("notes-clean-", "release-notes-")
SKIP_FILES = {"local.properties", "filter_msg.py"}

files = []
for root, dirs, names in os.walk("."):
    dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
    for name in names:
        path = os.path.join(root, name).replace("\\", "/")
        rel = path[2:] if path.startswith("./") else path
        if rel.startswith("app/build/"):
            continue
        if rel.endswith((".apk", ".aab", ".png", ".log")):
            continue
        if rel.startswith(SKIP_PREFIX) or rel in SKIP_FILES:
            continue
        files.append(rel)

files.sort()
print("files:", len(files))

items = []
for rel in files:
    content = open(rel, "rb").read()
    blob = api("POST", "/git/blobs", {
        "content": base64.b64encode(content).decode(),
        "encoding": "base64",
    })
    items.append({"path": rel, "mode": "100644", "type": "blob", "sha": blob["sha"]})

tree = api("POST", "/git/trees", {"tree": items})["sha"]
print("tree:", tree[:8])

commit = api("POST", "/git/commits", {
    "message": "feat: 颢博清理 v0.2.0\n\n"
               "安卓存储清理工具。10 个模块：常规清理、应用清理、残留清理、空文件夹、"
               "大文件、安装包、重复文件、存储分析、分类分析、应用管理。\n\n"
               "文件访问三层后端：SAF 授权 Android 目录 / Shizuku / 所有文件访问。",
    "tree": tree,
    "parents": [],
})["sha"]
print("commit:", commit[:8])

api("PATCH", "/git/refs/heads/main", {"sha": commit, "force": True})
print("BRANCH RESET DONE")
