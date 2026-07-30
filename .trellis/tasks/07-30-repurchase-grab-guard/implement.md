# Implement: 复购活动识别与 code107 处理

## Checklist（顺序）

1. [x] **模型** — `StoreInfo.java` 增加 `Boolean ifRepurchasePromotion` 及注释（活动类型，非账号资格）。
2. [x] **解析** — `XiaochanHttp.parsePromotion`：读 `if_repurchase_promotion`，回落 `promotion_condition.rp`；写入公共 `storeInfo` 后再 copy 到各平台分支。
3. [x] **手动/定时抢** — `GrabServiceImpl.doGrab`：`code==107` 时若 msg 空则规范化；确认不进入 code=4 重试（现有结构 + 注释）。
4. [x] **自动抢** — `AutoGrabServiceImpl.shouldSwitchAccount` 增加 `code == 107`；更新类头/判定表注释。
5. [x] **Spec** — `.trellis/spec/backend/xiaocan-rpc-contract.md` 增补字段与 code=107。
6. [x] **自检** — `mvn -DskipTests compile` → BUILD SUCCESS（2026-07-31）；走读 AC1–AC6；未改 OrderExchange/美团请求体。

## Validation

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
& "C:\D\tools\apache-maven-3.9.16\bin\mvn.cmd" -DskipTests compile
```

走读清单：

- [x] `parsePromotion` 对缺字段不 NPE（getBoolean/getJSONObject 空安全）
- [x] `shouldSwitchAccount(107)` true；`(6)` false
- [x] `doGrab` 中 `code != 4` 含 107 → break 且 push
- [x] 无「ifRepurchasePromotion 则直接 return 不调上游」

## Risky files

| 文件 | 风险 |
|------|------|
| `AutoGrabServiceImpl.java` | 换号表改错会影响 70/6 分流 |
| `GrabServiceImpl.java` | 重试条件误改会导致 4 不重试或 107 死循环 |
| `XiaochanHttp.parsePromotion` | NPE / 破坏多平台 copy |

## Rollback

`git checkout --` 上述 4 个 Java + 1 个 md；无迁移。

## Out of implement

- 前端角标、监控开关、资格预查询 API。

## Implementation notes (2026-07-31)

- 策略 A：不整类跳过复购；107 换号。
- 有上游 msg 时原样透传（HAR 已是完整中文句）；仅 msg 空时规范化。
)
