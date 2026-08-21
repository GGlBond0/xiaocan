# Implement: 部署 upstream 模块 JAR + 接口回归

## Checklist

1. [ ] 本地 `mvn -o clean package -DskipTests` 产出 `target/xiaocan.jar`(BUILD SUCCESS)
2. [ ] 确认 jar 含新控制器类(`jar tf` 查 `WmmtController`/`StoreSearchController` 等)
3. [ ] rsync 上传 `/opt/xiaocan/xiaocan.jar.new`(禁 scp)
4. [ ] 生产备份旧 JAR → 替换 → chown/chmod
5. [ ] `systemctl restart xiaocan` → 确认 active + 日志无启动错误 + HikariPool Started
6. [ ] 取生产 user 表真实 token,对 6 个新接口冒烟(favorite 走 save→stores→remove 闭环)
7. [ ] 更新 spec / 记忆,commit(不 push)

## Validation 命令

### 本地构建
```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
"/c/D/tools/apache-maven-3.9.16/bin/mvn.cmd" -o clean package -DskipTests
jar tf target/xiaocan.jar | grep -iE "WmmtController|StoreSearchController|FavoriteStoreController|StoreInventoryHistoryController"
```

### 传输(rsync,禁 scp)
```bash
rsync --partial -e ssh -av target/xiaocan.jar root@121.91.175.192:/opt/xiaocan/xiaocan.jar.new
```

### 生产部署
```bash
ssh root@121.91.175.192 "
  TS=$(date +%Y%m%d-%H%M%S)
  cp /opt/xiaocan/xiaocan.jar /opt/xiaocan/xiaocan.jar.bak.\$TS
  mv /opt/xiaocan/xiaocan.jar.new /opt/xiaocan/xiaocan.jar
  chown xiaocan:xiaocan /opt/xiaocan/xiaocan.jar && chmod 644 /opt/xiaocan/xiaocan.jar
  systemctl restart xiaocan
"
```

### 验证启动
```bash
ssh root@121.91.175.192 "systemctl is-active xiaocan; tail -30 /opt/xiaocan/logs/info.log"
# 期望:Started .../ HikariPool Start completed / Tomcat started on port 10234,无 ERROR/Exception
```

### 接口冒烟(取真实 token 后)
```bash
TOKEN=<从 user 表取>
# 1) 收藏闭环
curl -s -X POST http://127.0.0.1:8088/api/favorite/save -H "token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"locationId":1,"uniqueId":"smoke-test-unique","storeType":"XC_MANJIAN","name":"回归测试门店"}'
curl -s -X POST http://127.0.0.1:8088/api/favorite/stores -H "token: $TOKEN" -H "Content-Type: application/json" -d '{"pageNum":1,"pageSize":10}'
# 2) 库存历史
curl -s http://127.0.0.1:8088/api/store-inventory-history/smoke-test-unique -H "token: $TOKEN"
# 3) wmmt / store search(上游受限则降级)
curl -s -X POST http://127.0.0.1:8088/api/wmmt/shopList -H "token: $TOKEN" -H "Content-Type: application/json" -d '{}'
curl -s -X POST http://127.0.0.1:8088/api/store/search -H "token: $TOKEN" -H "Content-Type: application/json" -d '{}'
# 清理:按 uniqueId remove
```

> 冒烟走 nginx 反代 8088 或直接 127.0.0.1:10234 均可;带 `token` header。favorite 用"smoke-test-"前缀,回归后清理不残留。

## Rollback

新 JAR 启动失败或接口异常 → `ssh` 停机:`mv /opt/xiaocan/xiaocan.jar.bak.<ts> /opt/xiaocan/xiaocan.jar && systemctl restart xiaocan`(用 favorite 的 backup)。

## 交付物

- 部署成功 + 接口回归通过 ⇒ 更新 `upstream-modules-merge.md` 待办(标记"已部署")+ 记忆。
- commit(不 push)。
