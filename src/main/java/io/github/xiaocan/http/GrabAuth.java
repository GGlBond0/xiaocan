package io.github.xiaocan.http;

import io.github.xiaocan.model.entity.UserEntity;
import lombok.Builder;
import lombok.Data;

/**
 * 抢单登录态（从 UserEntity 映射）。
 * X-Nami 为空时由 {@link XiaochanHttp#getNami()} 随机生成。
 */
@Data
@Builder
public class GrabAuth {
    /**
     * X-Sivir 登录 JWT
     */
    private String sivir;
    /**
     * 小蚕用户id（X-Teemo / X-Vayne）
     */
    private Integer userId;
    /**
     * X-Session-Id
     */
    private String sessionId;
    /**
     * X-Nami（可选，默认随机）
     */
    private String nami;

    public static GrabAuth from(UserEntity user) {
        if (user == null) {
            return null;
        }
        return GrabAuth.builder()
                .sivir(user.getXcSivir())
                .userId(user.getXcUserId())
                .sessionId(user.getXcSessionId())
                .nami(user.getXcNami())
                .build();
    }

    /**
     * 登录态是否完整可用
     */
    public boolean isComplete() {
        return sivir != null && !sivir.isEmpty()
                && userId != null
                && sessionId != null && !sessionId.isEmpty();
    }
}
