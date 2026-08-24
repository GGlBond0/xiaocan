package io.github.xiaocan.service;

import io.github.xiaocan.model.dto.WmmtLoginStateDTO;
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
}