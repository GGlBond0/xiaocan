package io.github.xiaocan.tasks;

import com.alibaba.fastjson2.JSON;
import io.github.xiaocan.model.MinimumPayExtNotifyConfig;
import io.github.xiaocan.model.StoreExtNotifyConfig;
import io.github.xiaocan.model.StoreInfo;
import io.github.xiaocan.model.StoreKeywordExtNotifyConfig;
import io.github.xiaocan.model.entity.LocationEntity;
import io.github.xiaocan.model.entity.MonitorConfigEntity;
import io.github.xiaocan.model.entity.StorePushedHistoryEntity;
import io.github.xiaocan.model.entity.TaskExecHistoryEntity;
import io.github.xiaocan.model.entity.UserEntity;
import io.github.xiaocan.model.entity.WmmtLoginStateEntity;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import io.github.xiaocan.model.enums.MonitorTypeEnums;
import io.github.xiaocan.service.MonitoryConfigService;
import io.github.xiaocan.service.StorePushedHistoryService;
import io.github.xiaocan.service.UserService;
import io.github.xiaocan.service.WmmtLoginStateService;
import io.github.xiaocan.service.WmmtService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 歪麦（waimaimingtang）监控执行体。
 * 复用 BaseTask 的调度/去重/推送/历史/平台过滤管线，仅替换数据源为歪麦。
 * 数据源由 monitor_config.source 区分：source==2 才走本执行体。
 *
 * <p>本轮歪麦监控只通知不抢（autoGrab 对 source==2 强制 false，见 MonitoryConfigServiceImpl）。
 *
 * @author lz
 */
@Component
@Slf4j
public class WmmtTask extends BaseTask {

    @Resource
    private WmmtService wmmtService;
    @Resource
    private WmmtLoginStateService wmmtLoginStateService;
    @Resource
    private MonitoryConfigService monitoryConfigService;
    @Resource
    private StorePushedHistoryService storePushedHistoryService;
    @Resource
    private UserService userService;

    /** 歪麦接口固定城市（[[wmmt-foundation-ready]]） */
    /** 取该配置所属用户的全局去重/过期分钟数，null 兜底 60。与 StoreTask 一致。 */
    private int dedupMinutesOf(MonitorConfigEntity notifyConfig) {
        UserEntity user = userService.getById(notifyConfig.getUserId());
        if (user == null || user.getNotifyDedupMinutes() == null) return 60;
        // 下限 1，避免脏数据 0/负数导致去重窗口退化为立即过期
        return Math.max(1, user.getNotifyDedupMinutes());
    }

    /** 去重键：storeId/uniqId + promotionId。promotionId 为 null 时占位 "null"。 */
    private static String dedupKey(String storeKey, Integer promotionId) {
        return storeKey + ":" + (promotionId == null ? "null" : promotionId);
    }

    /** 门店键：优先 uniqId（歪麦用），回退 storeId。 */
    private static String storeKeyOf(StoreInfo s) {
        return StringUtils.hasText(s.getUniqId()) ? s.getUniqId() : String.valueOf(s.getStoreId());
    }

    /**
     * 静态兜底调度（仿 StoreTask.start），只跑 source==2 且未配置 cron 的歪麦配置。
     * cron 错开小蚕扫描（StoreTask :15 / MinimumPay :45），本处 :30，避免同刻撞上游。
     */
    @Scheduled(cron = "0 30 * * * ?")
    public void start() {
        try {
            List<MonitorConfigEntity> all = monitoryConfigService.listWithoutCron(
                    List.of(MonitorTypeEnums.STORE_ACTIVITY, MonitorTypeEnums.MINIMUM_PAY, MonitorTypeEnums.STORE_KEYWORD),
                    MonitorConfigStatusEnums.ENABLE);
            List<MonitorConfigEntity> wmmt = all.stream()
                    .filter(c -> Integer.valueOf(2).equals(c.getSource()))
                    .toList();
            if (!wmmt.isEmpty()) {
                log.info("开始执行 歪麦监控定时任务(静态兜底) 共 {} 个", wmmt.size());
                for (MonitorConfigEntity notifyConfig : wmmt) {
                    try {
                        execute(notifyConfig, false);
                    } catch (Exception e) {
                        log.error("歪麦监控静态兜底执行异常 configId: {}", notifyConfig.getId(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("执行歪麦监控静态兜底任务时发生异常", e);
        }
    }

    /** 按配置执行任务入口（cron 调度与静态兜底共用）。 */
    public void execute(MonitorConfigEntity notifyConfig, boolean cronDriven) {
        // 仅处理歪麦源；小蚕源交回 StoreTask，避免重复执行/串数据源
        if (!Integer.valueOf(2).equals(notifyConfig.getSource())) {
            return;
        }
        // STORE_ACTIVITY 当天去重（仿小蚕 StoreTask.checkRepeat）：当天已推过该门店则跳过，避免每轮重复推。
        // 歪麦门店键用 uniqId；小蚕用 storeId。
        if (notifyConfig.getType() == MonitorTypeEnums.STORE_ACTIVITY
                && checkRepeatToday(notifyConfig)) {
            log.info("configId: {} STORE_ACTIVITY 已推送过该门店，跳过执行", notifyConfig.getId());
            return;
        }
        super.runSingle(notifyConfig, cronDriven);
    }

    /** 歪麦 STORE_ACTIVITY 当天是否已推送过（按配置绑定的门店 uniqId 查当天历史）。 */
    private boolean checkRepeatToday(MonitorConfigEntity notifyConfig) {
        StoreExtNotifyConfig cfg = JSON.parseObject(notifyConfig.getExtConfig(), StoreExtNotifyConfig.class);
        if (cfg == null || cfg.getStoreInfo() == null || !StringUtils.hasText(cfg.getStoreInfo().getUniqId())) {
            return false;
        }
        return storePushedHistoryService.findByNotifyIdAndUniqIdToday(
                notifyConfig.getId(), cfg.getStoreInfo().getUniqId()) != null;
    }

    /**
     * 抓取歪麦门店活动。
     * STORE_ACTIVITY 用门店名作关键字；STORE_KEYWORD 用关键字；MINIMUM_PAY 拉全量。
     * 按 wmmtLoginStateIds 优先级取首个可用账号 token；全部失效记日志并返回空。
     */
    @Override
    protected List<StoreInfo> fetchStoreInfos(MonitorConfigEntity notifyConfig, TaskExecHistoryEntity execHistory, LocationEntity location) {
        execHistory.setNotifyType(notifyConfig.getType());
        String keyword;
        switch (notifyConfig.getType()) {
            case STORE_ACTIVITY -> {
                StoreExtNotifyConfig cfg = JSON.parseObject(notifyConfig.getExtConfig(), StoreExtNotifyConfig.class);
                keyword = (cfg == null || cfg.getStoreInfo() == null) ? null : cfg.getStoreInfo().getName();
            }
            case STORE_KEYWORD -> {
                StoreKeywordExtNotifyConfig cfg = JSON.parseObject(notifyConfig.getExtConfig(), StoreKeywordExtNotifyConfig.class);
                keyword = cfg == null ? null : cfg.getKeyword();
            }
            default -> keyword = null; // MINIMUM_PAY 拉全量
        }

        String token = resolveFirstToken(notifyConfig);
        if (!StringUtils.hasText(token)) {
            log.warn("configId: {} 歪麦账号列表全部无有效 token，跳过抓取", notifyConfig.getId());
            return List.of();
        }
        // storeType=null：不过滤业态（满减+美团赏金都返回），交给 filterStoreInfos 按活动语义过滤
        return wmmtService.fetchWmStoreInfos(token, null, location, keyword);
    }

    /**
     * 按 wmmtLoginStateIds 优先级取首个有效 token：
     * ids（或单值）逐个查歪麦账号，首个 token 非空即返回；全部无 token 返回 null。
     */
    private String resolveFirstToken(MonitorConfigEntity notifyConfig) {
        List<Integer> ids = parseWmmtAccountIds(
                notifyConfig.getWmmtLoginStateIds(), notifyConfig.getWmmtLoginStateId());
        if (ids.isEmpty()) {
            return null;
        }
        for (Integer id : ids) {
            try {
                WmmtLoginStateEntity account = wmmtLoginStateService.getOwnedById(id, notifyConfig.getUserId());
                if (account != null && StringUtils.hasText(account.getToken())) {
                    return account.getToken();
                }
            } catch (Exception e) {
                log.warn("configId: {} 歪麦账号 {} 不可用: {}", notifyConfig.getId(), id, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 解析歪麦账号优先级列表：优先 wmmtLoginStateIds（逗号串），空则回退单值。保序。
     * 与 MonitoryConfigServiceImpl.parseWmmtAccountIds 逻辑一致（两处独立实现，避免跨类访问私有方法）。
     */
    private static List<Integer> parseWmmtAccountIds(String ids, Integer singleId) {
        List<Integer> list = new ArrayList<>();
        if (ids != null && !ids.isBlank()) {
            for (String s : ids.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    try {
                        list.add(Integer.parseInt(t));
                    } catch (NumberFormatException ignore) { /* 跳过非法 */ }
                }
            }
        }
        if (list.isEmpty() && singleId != null) {
            list.add(singleId);
        }
        return list;
    }

    /**
     * 过滤歪麦门店活动，三种类型语义与小蚕 StoreTask 对齐。
     * 歪麦门店以 uniqId 为门店键（storeId 未填）。
     */
    @Override
    protected List<StoreInfo> filterStoreInfos(MonitorConfigEntity notifyConfig, List<StoreInfo> storeInfos) {
        if (storeInfos == null || storeInfos.isEmpty()) {
            return storeInfos == null ? List.of() : storeInfos;
        }
        if (notifyConfig.getType() == MonitorTypeEnums.STORE_ACTIVITY) {
            StoreExtNotifyConfig cfg = JSON.parseObject(notifyConfig.getExtConfig(), StoreExtNotifyConfig.class);
            StoreInfo target = cfg == null ? null : cfg.getStoreInfo();
            if (target == null || !StringUtils.hasText(target.getUniqId())) {
                log.warn("configId: {} STORE_ACTIVITY 缺歪麦门店 uniqId，无法匹配", notifyConfig.getId());
                return List.of();
            }
            String targetKey = target.getUniqId();
            return storeInfos.stream()
                    .filter(s -> storeKeyOf(s).equals(targetKey))
                    .filter(s -> s.getLeftNumber() != null && s.getLeftNumber() > 0)
                    // 返现金额必须 >= 配置的返现金额；价格必须 <= 配置的价格（与小蚕一致）
                    .filter(s -> target.getRebatePrice() == null
                            || (s.getRebatePrice() != null && s.getRebatePrice().compareTo(target.getRebatePrice()) >= 0))
                    .filter(s -> target.getPrice() == null
                            || (s.getPrice() != null && s.getPrice().compareTo(target.getPrice()) <= 0))
                    .toList();
        }

        // 批量去重：一次取本配置最近 dedupMin 分钟内已推送的 (storeKey, promotionId)
        // 门店键优先 uniqId（歪麦历史记录存 uniq_id），回退 storeId。
        int dedupMin = dedupMinutesOf(notifyConfig);
        Set<String> pushed = storePushedHistoryService.findPushedWithinMinutes(notifyConfig.getId(), dedupMin)
                .stream()
                .map(e -> {
                    String key = StringUtils.hasText(e.getUniqId()) ? e.getUniqId() : String.valueOf(e.getStoreId());
                    return dedupKey(key, e.getPromotionId());
                })
                .collect(Collectors.toSet());

        if (notifyConfig.getType() == MonitorTypeEnums.STORE_KEYWORD) {
            StoreKeywordExtNotifyConfig cfg = JSON.parseObject(notifyConfig.getExtConfig(), StoreKeywordExtNotifyConfig.class);
            return storeInfos.stream()
                    .filter(s -> s.getLeftNumber() != null && s.getLeftNumber() > 0)
                    .filter(s -> cfg.getLimitDistance() == null
                            || !cfg.getLimitDistance()
                            || (s.getDistance() != null && s.getDistance() <= 3500))
                    .filter(s -> !Boolean.TRUE.equals(cfg.getWithin3km())
                            || (s.getDistance() != null && s.getDistance() <= 3000))
                    .filter(s -> !pushed.contains(dedupKey(storeKeyOf(s), s.getPromotionId())))
                    .toList();
        }

        // MINIMUM_PAY：最小实付门槛 + 距离（含3km）+ 去重
        MinimumPayExtNotifyConfig cfg = JSON.parseObject(notifyConfig.getExtConfig(), MinimumPayExtNotifyConfig.class);
        java.math.BigDecimal minPay = cfg == null ? null : cfg.getMinimumPay();
        return storeInfos.stream()
                .filter(s -> s.getLeftNumber() != null && s.getLeftNumber() > 0)
                // 最小实付 = 满额 - 返现（歪麦 rebatePrice 已减会员补偿，见 WmmtHttp）
                .filter(s -> minPay == null
                        || (s.getPrice() != null && s.getRebatePrice() != null
                            && s.getPrice().subtract(s.getRebatePrice()).compareTo(minPay) <= 0))
                .filter(s -> !Boolean.TRUE.equals(cfg == null ? null : cfg.getWithin3km())
                        || (s.getDistance() != null && s.getDistance() <= 3000))
                .filter(s -> !pushed.contains(dedupKey(storeKeyOf(s), s.getPromotionId())))
                .toList();
    }

    /**
     * 清理本配置过期推送记录。STORE_ACTIVITY 走当天去重（execute 的 checkRepeatToday），
     * 其当天记录不应被 N 分钟清理误删，故 STORE_ACTIVITY 提前返回；其余类型（STORE_KEYWORD/MINIMUM_PAY）
     * 用 (storeKey, promotionId) 内存去重，历史定期清避免堆积。
     */
    @Override
    protected void cleanupExpired(MonitorConfigEntity notifyConfig) {
        if (notifyConfig.getType() == MonitorTypeEnums.STORE_ACTIVITY) {
            return;
        }
        try {
            int dedupMin = dedupMinutesOf(notifyConfig);
            int deleted = storePushedHistoryService.deleteByNotifyIdOlderThanMinutes(notifyConfig.getId(), dedupMin);
            if (deleted > 0) {
                log.info("configId: {} 清理 {} 分钟前的过期推送记录 {} 条", notifyConfig.getId(), dedupMin, deleted);
            }
        } catch (Exception e) {
            log.warn("configId: {} 清理过期推送记录失败", notifyConfig.getId(), e);
        }
    }
}