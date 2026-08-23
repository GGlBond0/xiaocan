package io.github.xiaochan;

import io.github.xiaocan.http.ProxyHolder;
import io.github.xiaocan.model.entity.ProxyConfigEntity;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProxyHolder 多池轮换核心逻辑单测（包内反射访问 private static 方法）。
 * 覆盖：parsePools 解析/过滤/空输入、resolveActUrl 替换/兜底。
 */
@Slf4j
public class ProxyHolderRoundRobinTest {

    @SuppressWarnings("unchecked")
    private static List<Integer> parsePools(String poolList) throws Exception {
        ProxyConfigEntity cfg = new ProxyConfigEntity();
        cfg.setPoolList(poolList);
        Method m = ProxyHolder.class.getDeclaredMethod("parsePools", ProxyConfigEntity.class);
        m.setAccessible(true);
        return (List<Integer>) m.invoke(null, (Object) cfg);
    }

    private static String resolveActUrl(String url, int poolId) throws Exception {
        Method m = ProxyHolder.class.getDeclaredMethod("resolveActUrl", String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, url, poolId);
    }

    @Test
    void parsePools_basic() throws Exception {
        assertEquals(List.of(51, 82, 57, 61, 62, 76), parsePools("51,82,57,61,62,76"));
    }

    @Test
    void parsePools_spacesAndEmpty() throws Exception {
        assertEquals(List.of(51, 82), parsePools(" 51 , 82 "));
        assertTrue(parsePools("").isEmpty());
        assertTrue(parsePools(null).isEmpty());
    }

    @Test
    void parsePools_invalidFiltered() throws Exception {
        assertEquals(List.of(51, 82), parsePools("51,abc,,0,1000,82"));
    }

    @Test
    void resolveActUrl_replacesPool() throws Exception {
        String url = "http://api.xiequ.cn/VAD/GetIp.aspx?act=getturn51&uid=183587&group=51&time=6";
        assertEquals("http://api.xiequ.cn/VAD/GetIp.aspx?act=getturn82&uid=183587&group=51&time=6",
                resolveActUrl(url, 82));
    }

    @Test
    void resolveActUrl_noMatch_returnsOriginal() throws Exception {
        String url = "http://example.com/path?x=1";  // 无 act=getturn
        assertEquals(url, resolveActUrl(url, 51));
        assertEquals(null, resolveActUrl(null, 51));
    }
}