package io.github.xiaocan.service;

import io.github.xiaocan.model.dto.WmmtLoginStateDTO;
import io.github.xiaocan.model.entity.WmmtLoginStateEntity;
import io.github.xiaocan.model.vo.WmmtLoginStateVO;

import java.util.List;

/**
 * 歪麦登录态池服务（多账号）。歪麦门店浏览无需账号，账号供未来抢单/监控引用。
 */
public interface WmmtLoginStateService {

    /**
     * 新增歪麦登录态（当前用户）。token 必填；name 空则默认"账号N"。
     * @return 保存后的实体 id
     */
    Integer save(WmmtLoginStateDTO dto);

    /**
     * 当前用户全部歪麦账号（token 掩码）
     */
    List<WmmtLoginStateVO> list();

    /**
     * 删除当前用户某账号（归属校验，非本人抛异常）
     */
    void delete(Integer id);

    /**
     * 按 id 取某用户（归属）的歪麦账号实体：校验存在且 userId 匹配，不匹配抛异常。
     * 定时任务（无 HTTP 上下文）显式传 ownerId 使用；service 层可经当前用户调用。
     *
     * @param id      歪麦账号 id
     * @param ownerId 归属系统用户 id
     * @return 歪麦账号实体（未删）
     */
    WmmtLoginStateEntity getOwnedById(Integer id, Integer ownerId);
}