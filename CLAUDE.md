# xiaocan 项目协作守则

本文件是**每次会话开局自动注入的行为指令**，只写"该怎么做"，细节（路径/密码/服务清单）在 auto-memory，按 `[[记忆名]]` 链接 recall。

## 运行期：去服务器，别在本地找

- 任何"看日志/运行报错/接口返回/systemd 状态/构建产物(jar)"的第一步是 `ssh root@121.91.175.192` 读远端，**不要先在本地仓库 `find *.log` / Grep**。本地仓库只有源码，无运行产物。→ [[runtime-logs-on-server]]、[[ssh-first-behavior]]
- 源码/配置/git 才在本地仓库找。

## 验证：用浏览器自动化，别只靠猜

- 前端上线效果/接口行为验证，优先用本机 browser-relay 驱动真实 Chrome。先 `curl http://127.0.0.1:18795/api/debug` 验活（`connected:true` 即可用）。→ [[browser-relay-setup]]
- 本会话若没有 `browser_*` 原生工具（MCP 需重启 Claude Code 才加载），用 HTTP API `curl http://127.0.0.1:18795/api/*` 兜底，功能等价。→ [[browser-relay-mcp-status]]

## 构建：永不在生产服务器跑 mvn

- 生产服务器同机跑多业务、内存仅 1.7G，跑 mvn 会拖垮其它服务。本地构建或 GitHub Actions。→ [[prod-build-avoid-server]]
- 会话 PATH 不带 mvn/java，编译用绝对路径（JDK17/Maven 路径见记忆）。→ [[local-build-toolchain]]、[[local-toolchain-inventory]]

## 部署：照实测，勿臆造

- 部署路径/服务名/步骤以 auto-memory 实测记录为准，**勿照任务文档臆造**（历史文档部署步骤常错）。动手前先 recall 部署拓扑。→ [[verify-deploy-claims]]、[[deploy-topology]]

---
细节查 auto-memory：`MEMORY.md` 索引。
