# Journal - lz (Part 1)

> AI development session journal
> Started: 2026-07-14

---



## Session 1: 部署小蚕到 Ubuntu 云服务器 + 动态代理绕过 WAF + 域名反代

**Date**: 2026-07-14
**Task**: 部署小蚕到 Ubuntu 云服务器 + 动态代理绕过 WAF + 域名反代

### Summary

在 Ubuntu 22.04 (121.91.175.192) 完成小蚕前后端分离部署：MySQL/Redis 复用+新建、后端 systemd 托管(env 注入密码, 强制绑 127.0.0.1)、前端 dist 经 Nginx 反代。8088 端口 + 域名 xiaocan.20030704.xyz 两种访问方式。fork workflow 重写(后端去硬编码) + 前端加镜像源。解决服务器公网 IP 被小蚕腾讯云 WAF 封禁(403)：给 XiaochanHttp 加动态代理池 ProxyHolder(取代理-缓存-套HTTP代理-403换代理重试)，配置经 env 注入；修 api.xiequ.cn DNS 污染(/etc/hosts 写死真实 IP)。验证搜索地址/活动列表恢复正常。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `23f4584` | (see git log) |
| `20a9f87` | (see git log) |
| `ac86c12` | (see git log) |
| `93230d3` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 2: distance-3km 后端生产部署 + Trellis 收尾

**Date**: 2026-07-14
**Task**: distance-3km 后端生产部署 + Trellis 收尾
**Branch**: `main`

### Summary

完成 distance-3km 后端部署：安装 gh CLI → PAT 触发 GitHub Actions build-prod.yml(run 29280098246) → 下载 xiaocan-prod-17 artifact → scp 到 /opt/xiaocan/xiaocan.jar → systemctl restart xiaocan。应用 4.47s 启动、HikariPool 连库成功、HTTP 200、定时任务恢复。修正 implement.md 臆造的部署步骤(后端 fork 实为 xiaocan、备份命名 xiaocan.jar.bak.<ts>、前端 fork 无 CI 待探明)。写入 deploy-topology / verify-deploy-claims 记忆。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `cad62c8` | (see git log) |
| `02f615b` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete
