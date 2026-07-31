# Implement: 代理按账号分配

## Checklist

1. [x] 改 `ProxyHolder`：按 key 缓存 + `getProxy(key,force)` + `invalidate(key)` + 全量 `invalidate()` + 兼容旧签名
2. [x] 改 `LotteryHttp.executeWithProxy`：传 silkId key；失败只 invalidate 该 key
3. [x] 改 `XiaochanHttp.executeWithProxy`：签名加 key；auth / 无 auth 分支
4. [x] 本地 `mvn -o -DskipTests compile` 通过（2026-07-24 BUILD SUCCESS）
5. [x] 部署生产 JAR + restart（2026-07-24 04:58，bak=xiaocan.jar.bak.20260724-045704，service active，HTTP 200）
6. [x] （同批）刷任务无 40s 间隔一并打包上线（JAR 内无 TASK_INTERVAL_MS）

## Validation

```powershell
# 本地（路径以 memory 为准）
& "C:\D\tools\apache-maven-3.9.16\bin\mvn.cmd" -o -DskipTests package
```

生产：
- `systemctl restart xiaocan` 后 `is-active`
- 刷两个账号任务，info.log 出现不同 key 的「获取代理」

## Rollback

- 恢复上一份 `/opt/xiaocan/xiaocan.jar.bak.*` 并 restart
