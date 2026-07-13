package io.github.xiaocan.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.xiaocan.config.BusinessException;
import io.github.xiaocan.http.GrabAuth;
import io.github.xiaocan.http.MessageHttp;
import io.github.xiaocan.http.XiaochanHttp;
import io.github.xiaocan.mapper.GrabConfigMapper;
import io.github.xiaocan.model.entity.GrabConfigEntity;
import io.github.xiaocan.model.entity.GrabHistoryEntity;
import io.github.xiaocan.model.entity.LocationEntity;
import io.github.xiaocan.model.entity.UserEntity;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import io.github.xiaocan.model.dto.GrabConfigDTO;
import io.github.xiaocan.model.dto.GrabLoginStateDTO;
import io.github.xiaocan.model.vo.GrabConfigVO;
import io.github.xiaocan.model.vo.GrabHistoryVO;
import io.github.xiaocan.model.vo.GrabResultVO;
import io.github.xiaocan.service.GrabService;
import io.github.xiaocan.service.LocationService;
import io.github.xiaocan.service.UserService;
import io.github.xiaocan.tasks.GrabCronScheduler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抢单服务实现
 */
@Slf4j
@Service
public class GrabServiceImpl extends ServiceImpl<GrabConfigMapper, GrabConfigEntity> implements GrabService {

    @Resource
    private UserService userService;
    @Resource
    private LocationService locationService;
    private final XiaochanHttp xiaochanHttp = new XiaochanHttp();
    @Resource
    @Lazy
    private GrabCronScheduler grabCronScheduler;

    /** 抓包 header 行解析：Key: Value（含大小写变体如 X-Sivir / x-Teemo） */
    private static final Pattern HEADER_LINE = Pattern.compile("(?i)^\\s*\"?([A-Za-z-]+)\"?\\s*[:：]\\s*\"?(.*?)\"?\\s*$");

    @Override
    public GrabResultVO saveLoginState(GrabLoginStateDTO dto) {
        UserEntity user = userService.getByCurrentRequest();
        String raw = dto.getRawHeaders();
        // 兼容抓包 JSON：从中提取 headers 节点
        if (raw.trim().startsWith("{")) {
            try {
                JSONObject json = JSONObject.parseObject(raw);
                JSONObject headers = json.getJSONObject("headers");
                if (headers != null) {
                    raw = headers.entrySet().stream()
                            .map(e -> e.getKey() + ": " + e.getValue())
                            .reduce((a, b) -> a + "\n" + b).orElse("");
                }
            } catch (Exception ignore) { }
        }
        String sivir = null, sessionId = null, nami = null, vayne = null;
        for (String line : raw.split("\\r?\\n")) {
            Matcher m = HEADER_LINE.matcher(line);
            if (!m.matches()) continue;
            String key = m.group(1).toLowerCase();
            String val = m.group(2).trim();
            if (val.startsWith("[")) { // 抓包导出值可能是 ["xxx"]
                val = val.replaceAll("[\\[\\]\"\\\\]", "");
            }
            switch (key) {
                case "x-sivir" -> sivir = val;
                case "x-session-id" -> sessionId = val;
                case "x-nami" -> nami = val;
                // x-Teemo 实为 silk_id，真实用户id取 X-Vayne 或 JWT.UserId
                case "x-vayne" -> vayne = val;
                default -> { }
            }
        }
        if (!StringUtils.hasText(sivir) || !StringUtils.hasText(sessionId)) {
            throw new BusinessException("未解析到登录态：缺少 X-Sivir 或 X-Session-Id");
        }
        Integer xcUserId = null;
        if (StringUtils.hasText(vayne)) {
            try { xcUserId = Integer.parseInt(vayne); } catch (Exception ignore) { }
        }
        // 从 JWT 取 UserId 兜底
        Long exp = parseJwtExp(sivir);
        if (xcUserId == null) {
            Integer jwtUid = parseJwtUserId(sivir);
            if (jwtUid != null) xcUserId = jwtUid;
        }
        if (exp != null && exp * 1000 < System.currentTimeMillis()) {
            throw new BusinessException("X-Sivir(JWT) 已过期，请重新抓包录入");
        }

        user.setXcSivir(sivir);
        user.setXcSessionId(sessionId);
        user.setXcUserId(xcUserId);
        user.setXcNami(StringUtils.hasText(nami) ? nami : null);
        user.setXcLoginUpdateTime(LocalDateTime.now());
        userService.updateById(user);

        GrabResultVO vo = new GrabResultVO();
        vo.setSuccess(true);
        vo.setCode(0);
        vo.setMsg("登录态已保存，用户id=" + xcUserId
                + (exp != null ? "，JWT过期时间=" + new Date(exp * 1000).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime() : ""));
        return vo;
    }

    @Override
    public GrabResultVO getLoginState() {
        UserEntity user = userService.getByCurrentRequest();
        GrabResultVO vo = new GrabResultVO();
        vo.setSuccess(StringUtils.hasText(user.getXcSivir()));
        Long exp = parseJwtExp(user.getXcSivir());
        if (exp != null) {
            long days = (exp - System.currentTimeMillis() / 1000) / 86400;
            vo.setMsg("用户id=" + user.getXcUserId() + "，JWT剩余约" + days + "天");
        } else {
            vo.setMsg(user.getXcSivir() == null ? "未绑定登录态" : "已绑定");
        }
        return vo;
    }

    @Override
    public void addUpdateConfig(GrabConfigDTO dto) {
        log.info("保存抢单配置请求: {}", dto);
        String cron = dto.getCron();
        if (StringUtils.hasText(cron)) {
            String trimmed = cron.trim();
            if (!CronExpression.isValidExpression(trimmed)) {
                throw new BusinessException("cron 表达式格式不正确");
            }
            dto.setCron(trimmed);
        } else {
            dto.setCron(null);
        }
        UserEntity user = userService.getByCurrentRequest();
        GrabConfigEntity entity;
        if (dto.getId() != null) {
            entity = getById(dto.getId());
            if (entity == null || !entity.getUserId().equals(user.getId())) {
                throw new BusinessException("无权修改该抢单配置");
            }
        } else {
            entity = new GrabConfigEntity();
            entity.setUserId(user.getId());
            entity.setStatus(MonitorConfigStatusEnums.ENABLE);
        }
        BeanUtils.copyProperties(dto, entity);
        // 默认值
        if (entity.getStorePlatform() == null) entity.setStorePlatform(1);
        if (entity.getIfAdvanceOrder() == null) entity.setIfAdvanceOrder(false);
        if (entity.getLeadMs() == null) entity.setLeadMs(0);
        if (entity.getEnableRetry() == null) entity.setEnableRetry(true);
        if (entity.getMaxRetry() == null) entity.setMaxRetry(3);
        if (entity.getRetryIntervalMs() == null) entity.setRetryIntervalMs(500);
        if (entity.getSilkId() == null) entity.setSilkId(0);
        saveOrUpdate(entity);
        grabCronScheduler.refresh(entity.getId());
    }

    @Override
    public List<GrabConfigVO> listByUserId() {
        Integer uid = userService.getByCurrentRequest().getId();
        return this.lambdaQuery().eq(GrabConfigEntity::getUserId, uid)
                .orderByDesc(GrabConfigEntity::getId).list().stream().map(e -> {
                    GrabConfigVO vo = new GrabConfigVO();
                    BeanUtils.copyProperties(e, vo);
                    return vo;
                }).toList();
    }

    @Override
    public void deleteById(Integer configId) {
        Integer uid = userService.getByCurrentRequest().getId();
        GrabConfigEntity entity = getById(configId);
        if (entity == null || !entity.getUserId().equals(uid)) {
            throw new BusinessException("无权操作");
        }
        removeById(configId);
        grabCronScheduler.cancel(configId);
    }

    @Override
    public void toggleStatus(Integer configId, MonitorConfigStatusEnums status) {
        Integer uid = userService.getByCurrentRequest().getId();
        GrabConfigEntity entity = getById(configId);
        if (entity == null || !entity.getUserId().equals(uid)) {
            throw new BusinessException("无权操作");
        }
        this.lambdaUpdate().eq(GrabConfigEntity::getId, configId)
                .set(GrabConfigEntity::getStatus, status).update();
        grabCronScheduler.refresh(configId);
    }

    @Override
    public GrabResultVO executeManual(Integer configId) {
        UserEntity user = userService.getByCurrentRequest();
        GrabConfigEntity config = getById(configId);
        if (config == null || !config.getUserId().equals(user.getId())) {
            throw new BusinessException("无权操作");
        }
        return doGrab(config, "MANUAL");
    }

    @Override
    public GrabResultVO doGrab(GrabConfigEntity config, String triggerType) {
        UserEntity user = userService.getById(config.getUserId());
        GrabResultVO result = new GrabResultVO();
        if (user == null) {
            result.setSuccess(false);
            result.setCode(-1);
            result.setMsg("用户不存在");
            return result;
        }
        GrabAuth auth = GrabAuth.from(user);
        if (auth == null || !auth.isComplete()) {
            result.setSuccess(false);
            result.setCode(-1);
            result.setMsg("未绑定小蚕登录态");
            saveHistory(config, user.getId(), false, -1, "未绑定小蚕登录态", null, 1, triggerType);
            return result;
        }
        // 解析位置（强制使用 locationId，与监控配置一致）
        String lat, lng; Integer cityCode;
        Optional<LocationEntity> loc = locationService.getOptById(config.getLocationId());
        if (loc.isEmpty()) {
            result.setSuccess(false);
            result.setCode(-1);
            result.setMsg("位置信息不存在");
            saveHistory(config, user.getId(), false, -1, "位置信息不存在", null, 1, triggerType);
            return result;
        }
        LocationEntity l = loc.get();
        lat = l.getLatitude();
        lng = l.getLongitude();
        cityCode = l.getCityCode();

        boolean retry = Boolean.TRUE.equals(config.getEnableRetry());
        int maxRetry = retry ? Math.max(1, config.getMaxRetry() == null ? 1 : config.getMaxRetry()) : 1;
        int interval = config.getRetryIntervalMs() == null ? 500 : config.getRetryIntervalMs();

        GrabResultVO finalResult = null;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            LocalDateTime start = LocalDateTime.now();
            JSONObject resp;
            try {
                resp = xiaochanHttp.grabPromotionQuota(auth, cityCode, lat, lng,
                        config.getPromotionId(), config.getSilkId());
            } catch (Exception e) {
                log.error("抢单请求异常 configId={}", config.getId(), e);
                saveHistory(config, user.getId(), false, -1, "请求异常:" + e.getMessage(), null, attempt, triggerType);
                finalResult = fail(-1, "请求异常:" + e.getMessage());
                if (attempt < maxRetry && retry) sleep(interval); else break;
                continue;
            }
            JSONObject status = resp.getJSONObject("status");
            int code = status != null ? status.getIntValue("code") : -1;
            String msg = status != null ? status.getString("msg") : "";
            Long orderId = resp.getLong("promotion_order_id");
            boolean success = (code == 0 && orderId != null);
            saveHistory(config, user.getId(), success, code, msg, orderId, attempt, triggerType);

            finalResult = new GrabResultVO();
            finalResult.setCode(code);
            finalResult.setMsg(msg);
            finalResult.setPromotionOrderId(orderId);
            finalResult.setSuccess(success);

            if (success) {
                // 成功：落订单id、停用、推送
                this.lambdaUpdate().eq(GrabConfigEntity::getId, config.getId())
                        .set(GrabConfigEntity::getPromotionOrderId, orderId)
                        .set(GrabConfigEntity::getLastResult, "成功 orderId=" + orderId)
                        .set(GrabConfigEntity::getLastGrabTime, LocalDateTime.now())
                        .set(GrabConfigEntity::getStatus, MonitorConfigStatusEnums.DISABLE)
                        .update();
                grabCronScheduler.cancel(config.getId());
                push(user, "抢单成功", "活动" + config.getPromotionId() + " 抢到，订单号 " + orderId);
                break;
            }
            // code=6 名额已抢完 / 其它非未开始错误：不重试
            if (code != 4) {
                push(user, "抢单失败", "活动" + config.getPromotionId() + " 失败：" + msg + "(code=" + code + ")");
                break;
            }
            // code=4 活动未开始：重试
            if (attempt < maxRetry && retry) {
                sleep(interval);
            } else {
                push(user, "抢单失败", "活动" + config.getPromotionId() + " 重试" + maxRetry + "次仍为未开始/失败");
            }
        }
        if (finalResult == null) finalResult = fail(-1, "未知失败");
        return finalResult;
    }

    @Override
    public List<GrabHistoryVO> listHistoryByUserId(Integer limit) {
        Integer uid = userService.getByCurrentRequest().getId();
        List<GrabConfigEntity> configs = this.lambdaQuery()
                .eq(GrabConfigEntity::getUserId, uid).select(GrabConfigEntity::getId).list();
        if (configs.isEmpty()) return List.of();
        List<Integer> ids = configs.stream().map(GrabConfigEntity::getId).toList();
        int lim = (limit == null || limit <= 0) ? 50 : limit;
        List<GrabHistoryEntity> list = grabHistoryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GrabHistoryEntity>()
                        .in(GrabHistoryEntity::getGrabConfigId, ids)
                        .orderByDesc(GrabHistoryEntity::getId)
                        .last("limit " + lim));
        return list.stream().map(e -> {
            GrabHistoryVO vo = new GrabHistoryVO();
            BeanUtils.copyProperties(e, vo);
            return vo;
        }).toList();
    }

    private void saveHistory(GrabConfigEntity config, Integer userId, boolean success, int code,
                             String msg, Long orderId, int attempt, String triggerType) {
        GrabHistoryEntity h = new GrabHistoryEntity();
        h.setUserId(userId);
        h.setGrabConfigId(config.getId());
        h.setPromotionId(config.getPromotionId());
        h.setStartTime(LocalDateTime.now());
        h.setEndTime(LocalDateTime.now());
        h.setSuccess(success);
        h.setRespCode(code);
        h.setRespMsg(msg);
        h.setPromotionOrderId(orderId);
        h.setAttempt(attempt);
        h.setTriggerType(triggerType);
        grabHistoryMapper.insert(h);
    }

    @Resource
    private io.github.xiaocan.mapper.GrabHistoryMapper grabHistoryMapper;

    private void push(UserEntity user, String summary, String body) {
        try {
            if (StringUtils.hasText(user.getSpt())) {
                MessageHttp.sendMessage(user.getSpt(), body, summary);
            }
        } catch (Exception e) {
            log.error("推送抢单结果失败", e);
        }
    }

    private GrabResultVO fail(int code, String msg) {
        GrabResultVO r = new GrabResultVO();
        r.setSuccess(false);
        r.setCode(code);
        r.setMsg(msg);
        return r;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** 解析 JWT exp（秒），失败返回 null */
    private Long parseJwtExp(String jwt) {
        JSONObject p = parseJwtPayload(jwt);
        return p == null ? null : p.getLong("exp");
    }

    private Integer parseJwtUserId(String jwt) {
        JSONObject p = parseJwtPayload(jwt);
        return p == null ? null : p.getInteger("UserId");
    }

    private JSONObject parseJwtPayload(String jwt) {
        if (!StringUtils.hasText(jwt)) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) return null;
        try {
            byte[] d = Base64.getUrlDecoder().decode(parts[1]);
            return JSONObject.parse(new String(d, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}
