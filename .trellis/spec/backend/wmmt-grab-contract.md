# Wmmt (歪麦) 抢单契约

> 歪麦「立即抢购/报名」正向提交接口与自动抢单契约。逆向来源：反编译小程序源码
> `wx5762f23d920ad9e5`（2026-08-24 会话，task `08-24-wmmt-grab`）。加解密基础见 [[wmmt-contract]]、[[wmmt-crypto-cracked]]。

## 1. 直接抢购提交接口（双轨制，由 newSignUpFlag 决定）

| 轨 | 接口(production) | 网关 | 加密方式 | 成功判据 |
|---|---|---|---|---|
| **新版** | `POST /order/waimaimt-web-order/overbear/signup` | `baseURL2`=`https://wmapp-api-v2.waimaimingtang.com/api` | **encryptedRequest** RSA+AES（`encrypt-key` 头，同门店列表加密） | `code∈{200,0,"0"}` 且 `data.buyOverbearId` 非空 |
| **老版** | `POST /api/v2/overbearfood/api_overbear_sign_up` | `baseURL`=`https://fz-gateway.waimaimingtang.com/api/` | **request** LEGACY_AES（`{json: AES(data)}`，data 字段 AES 解） | `code==1` |
| **美团动手餐(独立)** | `POST /order/waimaimt-web-order/meituan/signup` | baseURL2 | encryptedRequest RSA+AES | `code∈{200,0,"0"}` + `buyOverbearId` |

**选轨**：`newSignUpFlag`（服务端下发，`newServiceConfig` 响应 `data.newSignUpFlag` 布尔存 storage）为真
或用 page `"km"` → 新版（encryptedRequest）；否则老版。**后端必须动态读 newSignUpFlag，不能写死。**

## 2. 请求体契约（外卖霸王餐 overbear/signup）

### 新版（encryptedRequest，body 直接 AES+encrypt-key）
```json
{
  "businessId": "<门店id, ops.id>",
  "overbearfoodId": "<活动/商品id>",
  "serviceNoStr": "api_overbear_sign_up",
  "userId": "<歪麦用户id(数字)>",
  "buyChannel": "autonomy",
  "type": "overbear_one",
  "shareRecordId": "",
  "shareLatitude": "",
  "shareLongitude": "",
  "shareUserId": "",
  "redIds": ["<红包id数组>"],
  "province": "<省>",
  "city": "<市>",
  "area": "<区>",
  "orderSourcePage": "<抢单来源页>"
}
```

### 老版（request，`{json: AES(data)}`，LEGACY_AES）
同字段，**无 `orderSourcePage`**。

### 美团餐（meituan/signup，encryptedRequest）
```
userId / businessId / buyChannel="autonomy" / serviceNoStr="api_meituan_overbear_sign_up" /
shareUserId,Longitude,Latitude,RecordId="" / poiEventId / meituanAccount / channelId="" /
redId(注意单数) / meituanCommissionType
```
`shopPlatformType` 决定外卖（overbear）还是美团（meituan）分支。

## 3. 请求头（两种轨都复用 `WmmtHttp.buildCommonHeaders` 基础）
`token`(header)=`value.userToken`（32 位 hex，**不是 userId**）；`application=overbear_one`；
`nonce/timestamp/sign=AES(timestamp+nonce, SIGN_AES_KEY)`；`city=URLEncode(addres.city)`；
`appversion=1.1.175`。新版追加 `encrypt-key` 头（`base64(AESkey)→RSA(publicKey)`），body=AES(data)。

## 4. 关键字段来源（逆向实测）
| 字段 | 来源 |
|---|---|
| `token`(header) | 登录 `api_user_h5_wx_login` 返回 `data.userToken` |
| `userId`(body,数字) | 登录返回 `data.userId`（**≠ token**）——**故 wmmt_login_state 需补存 userId** |
| `businessId` | 门店详情参数 `ops.id`（= 门店 id） |
| `overbearfoodId` | 门店列表/详情活动项 `overBearFoodId` |
| `province/city/area` | 地址 `addres.prov/city/area`（与城市接口相关） |
| `redIds` | 选中红包 id 数组（`maxUserRedPackage.id` 等） |

## 5. 登录 / 用户信息接口（userId 反查无解，必须存储）
- 登录：`POST fz-gateway.../api/api/v2/user/login/api_user_h5_wx_login`，body
  `{serviceNoStr:"api_user_h5_wx_login", code, application, cityName, province, district, channelId, shareUserId}`，
  返回 `data:{userId(数字), userToken(32hex), status, ...}`。`token=userToken`。
- 用户信息 `POST /api/v2/user/api_user_info_one`，body `{serviceNoStr, city, userId}` 也要 userId。
- **结论**：userId 只可能在登录时拿到；无免授权 interface 反查。**wmmt_login_state 必须补 `userId` 列**。

## 6. 访问只读报名/订单接口（供参考）
- 我的报名：`POST /api/v2/overbearfood/api_overbear_orders_list`，body
  `{page, limit, queryDataType:"query_data_submitted", serviceNoStr, userId, version}` → 也需 userId。
- 取消报名：`api_overbear_cancel_sign_up`；抢前检查 `api_wait_submit_check_return`。

## 7. 自动抢单链路（对接现有 AutoGrabService 体系）
- `monitor_config` source=2 命中 → `AutoGrabService.tryCreateFromMonitor`（**需按 source 分支**）
  → 组装歪麦 `grab_config`（复用表，`promotion_id` 承载歪麦活动键、`store_platform` 语义待定）
  → `grabService.doGrab` 内**按 source 分支调歪麦** signup → 成功/失败入 `grab_history` + 推送。
- 歪麦 `newSignUpFlag` 在 `WmmtHttp.fetchKeys` 拉密钥时一并缓存。

## 8. 边界与风险（逆向所见）
- **报名费**：`data.payAmount>0` 或 `occupyPayAmount>0`/`secKillPayAmount>0` → 需微信支付
  `requestPayment`（timeStamp/nonceStr/package/signType=MD5/paySign）。**自动抢单遇报名费>0 需提示无法自动支付**，不能静默失败。
- **资格约束**：复购活动 `isRepurchaseActivity` + `hasCurrentStoreCompletedOrder`；信用分/品鉴意见门槛；`userInfo.vipType`。
- 部分业务（`api_overbear_business_take_away_index_list`）仅 APP 端可抢，接口可能拒绝。

## 9. 校验与错误矩阵（待实现时补）
| 条件 | 预期 |
|---|---|
| source=2 抢单但配置未绑歪麦账号 | BusinessException，提示需选歪麦账号 |
| wmmt_login_state 无 userId | 提示需补录 userId（本轮新字段） |
| newSignUpFlag 取不到 | 回退老版（LEGACY_AES），记 warn |
| 报名费>0 | 记失败 + 推送「需手动支付报名费」，不再继续 |

## 10. 相关
[[wmmt-contract]] [[wmmt-crypto-cracked]] [[wmmt-foundation-ready]] [[wmmt-monitor-notify]] [[login-state-unified-pool]]