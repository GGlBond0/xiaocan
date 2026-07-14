package io.github.xiaocan.http;

import io.github.xiaocan.model.entity.MerchantBlacklistEntity;
import io.github.xiaocan.service.MerchantBlacklistService;
import io.github.xiaocan.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 商家名称关键字黑名单持有者：从全局配置（数据库 merchant_blacklist_config 表）读取
 * 黑名单规则，TTL 内存快照缓存，失效可重读。保存配置后经 invalidate() 即时生效，无需重启。
 *
 * 规则文本解析（与 design.md 对齐）：
 * - 按行切分 -> 去首尾空白 -> 忽略空行 -> 每行按 '&' 切分多词 -> 去空白 -> 得 List<List<String>> 规则集
 * - 匹配(storeName): 若 enabled 且规则集非空 且 storeName != null：
 *     对每条规则(词列表): 规则命中 = 该行所有词都以子串形式出现在 lower(storeName) 中
 *     任一行命中 => isBlacklisted = true（行内 AND，多行 OR）
 *   否则 => false
 * - 大小写不敏感：storeName 与词统一 toLowerCase 后用 contains 比较。
 *
 * 异常/容器未就绪回退"禁用+空规则"（即 isBlacklisted 恒 false），保证不阻断监控/抢单主流程，
 * 对齐 ProxyHolder 兜底哲学。
 */
@Slf4j
public class MerchantBlacklistHolder {

    /** 配置内存快照：减少运行时打 DB 频率 */
    private static volatile MerchantBlacklistEntity cfgSnapshot;
    private static volatile long cfgLoadedAt;
    /** 配置快照刷新间隔（毫秒）：保存后 invalidate() 也会立即清快照，无需等过期 */
    private static final long CFG_TTL = 5000L;

    /** 解析后的规则集快照（与 cfgSnapshot 同期更新，避免每次匹配重解析） */
    private static volatile List<List<String>> rulesSnapshot = Collections.emptyList();
    private static volatile boolean enabledSnapshot = false;

    /** 禁用占位快照：DB 不可用 / 容器未就绪时写入，使 isBlacklisted 恒 false 且 CFG_TTL 节流生效 */
    private static final MerchantBlacklistEntity DISABLED_PLACEHOLDER = createDisabledPlaceholder();

    private static MerchantBlacklistEntity createDisabledPlaceholder() {
        MerchantBlacklistEntity ph = new MerchantBlacklistEntity();
        ph.setId(0);
        ph.setEnabled(false);
        ph.setKeywords(null);
        ph.setDeleted(false);
        return ph;
    }

    /**
     * 判断商家名是否命中黑名单。供监控/抢单调用方零成本接入。
     * storeName==null、enabled=false、规则集空、异常回退时均返回 false（不过滤）。
     */
    public static boolean isBlacklisted(String storeName) {
        if (storeName == null) {
            return false;
        }
        loadCfg();
        if (!enabledSnapshot || rulesSnapshot.isEmpty()) {
            return false;
        }
        String lowerName = storeName.toLowerCase();
        for (List<String> rule : rulesSnapshot) {
            if (rule.isEmpty()) {
                continue;
            }
            boolean allMatch = true;
            for (String word : rule) {
                if (!lowerName.contains(word)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return true;
            }
        }
        return false;
    }

    /**
     * 失效缓存：清配置与规则快照，使下次判断重读 DB。
     * MerchantBlacklistServiceImpl.updateConfig 落库后调用本方法实现即时生效。
     */
    public static synchronized void invalidate() {
        cfgSnapshot = null;
        cfgLoadedAt = 0;
        rulesSnapshot = Collections.emptyList();
        enabledSnapshot = false;
    }

    /**
     * 读取配置快照：CFG_TTL 内复用内存快照，过期或无快照则从 MerchantBlacklistService 重读。
     * service 不可用（容器未就绪/异常）时回退"禁用+空规则"，不阻断主流程。
     * 注意：service 调用在锁外执行，避免持锁时进入 Service 实例锁形成反向锁顺序。
     */
    public static void loadCfg() {
        MerchantBlacklistEntity snap = cfgSnapshot;
        if (snap != null && System.currentTimeMillis() - cfgLoadedAt < CFG_TTL) {
            return;
        }
        // 锁外调 service 取最新 entity
        MerchantBlacklistEntity entity = null;
        try {
            MerchantBlacklistService service = SpringContextUtil.getBean(MerchantBlacklistService.class);
            entity = service.getEntity();
        } catch (Exception e) {
            log.warn("读取商家黑名单配置失败，回退为不过滤: {}", e.getMessage());
        }
        if (entity == null) {
            // 异常/容器未就绪：回退"禁用+空规则"，保证 isBlacklisted 恒 false，不阻断主流程。
            // 写一个禁用占位快照并刷新 cfgLoadedAt，使 CFG_TTL 节流生效，避免 DB 不可用时每次调用都重试。
            synchronized (MerchantBlacklistHolder.class) {
                cfgSnapshot = DISABLED_PLACEHOLDER;
                cfgLoadedAt = System.currentTimeMillis();
                enabledSnapshot = false;
                rulesSnapshot = Collections.emptyList();
            }
            return;
        }
        // 锁内仅写快照
        synchronized (MerchantBlacklistHolder.class) {
            cfgSnapshot = entity;
            cfgLoadedAt = System.currentTimeMillis();
            enabledSnapshot = Boolean.TRUE.equals(entity.getEnabled());
            rulesSnapshot = parseRules(entity.getKeywords());
        }
    }

    /**
     * 解析关键字规则文本为规则集：按行切分 -> 去空白 -> 忽略空行 -> 每行按 '&' 切分多词 -> 去空白。
     * 所有词统一 toLowerCase，便于匹配时直接比较。
     */
    private static List<List<String>> parseRules(String keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return Collections.emptyList();
        }
        String[] lines = keywords.split("\\R");
        List<List<String>> rules = new ArrayList<>();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] words = trimmed.split("&");
            List<String> wordList = new ArrayList<>(words.length);
            for (String w : words) {
                if (w == null) {
                    continue;
                }
                String wt = w.trim().toLowerCase();
                if (!wt.isEmpty()) {
                    wordList.add(wt);
                }
            }
            if (!wordList.isEmpty()) {
                rules.add(wordList);
            }
        }
        return rules;
    }
}
