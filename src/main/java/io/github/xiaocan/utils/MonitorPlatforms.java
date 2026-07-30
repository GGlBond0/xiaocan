package io.github.xiaocan.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 监控配置生效平台解析（推送 + 自动抢单共用）。
 * <p>
 * 约定：null/空 → 三平台全开 [1,2,3]；有值按逗号解析，只保留 1/2/3，保序去重。
 */
public final class MonitorPlatforms {

    private static final Logger log = LoggerFactory.getLogger(MonitorPlatforms.class);

    public static final int MEITUAN = 1;
    public static final int ELEME = 2;
    public static final int JD = 3;

    /** 空值默认：全开（与历史「空=仅美团」不同，见 07-30-monitor-platform-filter-push） */
    private static final List<Integer> ALL = List.of(MEITUAN, ELEME, JD);

    private MonitorPlatforms() {
    }

    /**
     * 解析生效平台有序列表。
     * null/blank 或解析后无合法码 → 全开 [1,2,3]（非法串运行期 warn，避免静默停推）。
     */
    public static List<Integer> parseEffective(String grabPlatforms) {
        if (!StringUtils.hasText(grabPlatforms)) {
            return new ArrayList<>(ALL);
        }
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        for (String s : grabPlatforms.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                int code = Integer.parseInt(t);
                if (isKnown(code)) {
                    seen.add(code);
                }
            } catch (NumberFormatException ignore) {
                // skip invalid token
            }
        }
        if (seen.isEmpty()) {
            // 保存期应拒绝；运行期兜底全开，避免静默停推
            log.warn("grabPlatforms 无合法平台码，运行期兜底全开: raw={}", grabPlatforms);
            return new ArrayList<>(ALL);
        }
        return new ArrayList<>(seen);
    }

    /**
     * 保存校验：non-null 串必须至少含 1 个合法平台码。
     * null/blank 视为未配置（运行期全开），返回 true。
     */
    public static boolean isValidForSave(String grabPlatforms) {
        if (!StringUtils.hasText(grabPlatforms)) {
            return true;
        }
        return !extractValidOrdered(grabPlatforms).isEmpty();
    }

    /**
     * 规整化保存串：合法码保序去重后 join；null/blank → null；无合法码 → null（调用方应先 isValidForSave）。
     */
    public static String normalizeForSave(String grabPlatforms) {
        if (!StringUtils.hasText(grabPlatforms)) {
            return null;
        }
        List<Integer> codes = extractValidOrdered(grabPlatforms);
        if (codes.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Integer c : codes) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static boolean isKnown(int code) {
        return code == MEITUAN || code == ELEME || code == JD;
    }

    public static boolean contains(List<Integer> platforms, Integer storeType) {
        if (storeType == null || platforms == null || platforms.isEmpty()) {
            return false;
        }
        return platforms.contains(storeType);
    }

    private static List<Integer> extractValidOrdered(String grabPlatforms) {
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        for (String s : grabPlatforms.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                int code = Integer.parseInt(t);
                if (isKnown(code)) {
                    seen.add(code);
                }
            } catch (NumberFormatException ignore) {
                // skip
            }
        }
        return new ArrayList<>(seen);
    }

    /** 便于测试/日志：不可变全开视图 */
    public static Set<Integer> allPlatformSet() {
        return Set.copyOf(ALL);
    }
}
