# Logging Guidelines

> 日志约定。

---

## Overview

日志框架 Logback，配置 `src/main/resources/logback.xml`。类级统一用 Lombok `@Slf4j` 注入 SLF4J logger（连非 Spring bean 的 `http/` 工具类也用）。

---

## Log Levels

- `log.info`：正常流程、调度记录、请求记录。例 `log.info("保存监控配置请求: {}", dto)`。
- `log.warn`：校验失败、可恢复异常（如代理 403 重试）、`BusinessException`。例 `log.warn("{} 经代理 {}:{} 返回 403，换代理重试", tag, ip, port)`。
- `log.error`：异常、发送失败、执行失败。异常对象作为**最后一个参数**传入（不占位符）：`log.error("请求失败: {}", body, e)`。

---

## Structured Logging

- 用 SLF4J 占位符 `{}`，**不要字符串拼接**：`log.info("configId: {}", id)`。
- 异常对象作为最后参数，不写 `{}`：`log.error("xxx error", e)`。
- Logback pattern 含 `%X{traceId}` MDC 占位符，但**代码中未见设置 traceId**（既有现状，MDC 占位为空）。

### Logback 配置（`logback.xml`）
- 三个 appender：
  - `CONSOLE`（INFO 阈值）
  - `INFO_FILE` → `logs/info.log`，按天滚动，保留 3 天
  - `ERROR_FILE` → `logs/error.log`
- root level = `DEBUG`。
- 生产部署后实际输出到 `/opt/xiaocan/logs/info.log` + `error.log`（见部署 memory）。

---

## What to Log

- 监控配置保存请求（`MonitorController`）。
- 定时任务调度：开始执行、configId、type、命中条件（`BaseTask`/`MonitorCronScheduler`）。
- 外部 HTTP 请求：城市/经纬度/最大数量（`XiaoChanServiceImpl`「请求小产列表，城市: ...」）。
- 代理获取/失效：`获取代理: {ip}:{port}`、403 换代理重试（`ProxyHolder`/`XiaochanHttp`）。
- 代理 API 异常、无可用代理（`ProxyHolder.fetchProxy`）。

---

## What NOT to Log

- **MySQL 密码、PROXY_API_URL 的 vkey、token** 等凭据——`xiaocan.env` 含真实凭据，勿 log。
- 上游返回的完整响应体（可能含大量数据）仅在错误时 log.error，正常流程不 dump。
- 用户敏感信息。
