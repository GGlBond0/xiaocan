# Design: 部署 upstream 模块 JAR + 接口回归

## 目标形态

生产 `xiaocan.service` 从旧 JAR 换到含五模块控制器的新 JAR。架构不变(nginx 反代 `/api/` → 127.0.0.1:10234),仅二进制替换 + 重启,不重启 MySQL/nginx/其它服务。

## 部署链路(照实测)

```
本地 mvn -o clean package -DskipTests
  → target/xiaocan.jar (~43MB)
rsync --partial -e ssh → /opt/xiaocan/xiaocan.jar.new   (禁 scp,见 [[scp-large-jar-hangs-server]])
ssh:
  backup  cp /opt/xiaocan/xiaocan.jar xiaocan.jar.bak.<ts>
  replace mv xiaocan.jar.new xiaocan.jar + chown xiaocan:xiaocan + chmod 644
  restart systemctl restart xiaocan
  verify active + 日志无启动错误 + HikariPool Started
```

## 关键事实(已核实)

| 项 | 结论 |
|---|---|
| 传输 | 用 `rsync --partial`。43MB 直接 scp 会卡死小机([[scp-large-jar-hangs-server]]) |
| 构建 | 本地 package。代理逻辑(ProxyHolder/executeWithProxy)已在分支内,自动编入 |
| 重启风险 | 服务 active;内存 available 371M;重启窗口内接口短暂不可用(可接受) |
| 登录态 | 新接口用 `token` header → `user.token` 列(`getByCurrentRequest`→`getByToken`)。**非** login_state 表 |
| allowPublicKeyRetrieval | 已在 application.yaml(占位符注入),生产 EnvironmentFile 覆盖 user/pwd。**严禁用 GitHub Actions 构建** |
| 验证方式 | 经 nginx 反代 80/8088,带有效 token 冒烟 |

## 接口回归面(新控制器,全部 POST/GET,需 `token` header)

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/wmmt/shopList` | POST | 歪麦门店列表(游标翻页) `WmmtShopListDTO` |
| `/api/store/search` | POST | 聚合搜索(小蚕满减/美团赏金/歪麦) `StoreSearchDTO` |
| `/api/favorite/save` | POST | 收藏 `SaveFavoriteDTO`(locationId/uniqueId/storeType/name) |
| `/api/favorite/stores` | POST | 查收藏 `FavoriteStoreQueryDTO` |
| `/api/favorite/{id}` | DELETE | 删收藏 |
| `/api/store-inventory-history/{uniqueId}` | GET | 当日库存历史 |

## 回归策略

- 从生产 `user` 表取一个真实 `token`(手工/脚本)作冒烟凭据,避免污染 login_state。
- 对每个接口发请求,断言 HTTP 200 + `BaseResult` 正常结构(非 401/500)。
- favorite:走 save → stores 验证落库 → remove 清理,不残留脏数据。
- 回归后确认主线能力(抢单/代理/登录态池)未受新 JAR 影响(服务正常启动即说明装配 OK)。

## Rollback

- 保留 `xiaocan.jar.bak.<ts>`;若新 JAR 启动失败或接口异常,`mv` 回旧 JAR 并 `systemctl restart xiaocan` 即回滚。
- 若失败具体到新表/新列,生产 SQL 已落地,可 DROP(见 schema 任务 design)。

## 风险

- 旧 JAR 与新 JAR 差别大(新增 8 字段实体/静态转实例代理),首次用新配置启动需重点盯启动日志。
- 新接口依赖 `waimaiToken`/`storeTypeEnum` 等上游字段,部分依赖真实上游抓取;冒烟若上游受限,可降级为"接口可达 + 装配成功"。
