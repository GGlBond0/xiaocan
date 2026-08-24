package io.github.xiaocan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.xiaocan.config.BusinessException;
import io.github.xiaocan.mapper.WmmtLoginStateMapper;
import io.github.xiaocan.model.dto.WmmtLoginStateDTO;
import io.github.xiaocan.model.entity.UserEntity;
import io.github.xiaocan.model.entity.WmmtLoginStateEntity;
import io.github.xiaocan.model.vo.WmmtLoginStateVO;
import io.github.xiaocan.service.UserService;
import io.github.xiaocan.service.WmmtLoginStateService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 歪麦登录态池服务实现（多账号）。歪麦门店浏览无需账号，账号供未来抢单/监控引用。
 */
@Slf4j
@Service
public class WmmtLoginStateServiceImpl implements WmmtLoginStateService {

    /** 歪麦当前固定城市 */
    private static final String CITY = "长沙市";

    @Resource
    private UserService userService;
    @Resource
    private WmmtLoginStateMapper wmmtLoginStateMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer save(WmmtLoginStateDTO dto) {
        UserEntity user = userService.getByCurrentRequest();
        WmmtLoginStateEntity entity = new WmmtLoginStateEntity();
        entity.setUserId(user.getId());
        entity.setToken(dto.getToken().trim());
        entity.setCity(StringUtils.hasText(dto.getCity()) ? dto.getCity() : CITY);
        if (StringUtils.hasText(dto.getName())) {
            entity.setName(dto.getName().trim());
        } else {
            // 默认别名：当前用户第几个账号
            long count = wmmtLoginStateMapper.selectCount(
                    new LambdaQueryWrapper<WmmtLoginStateEntity>()
                            .eq(WmmtLoginStateEntity::getUserId, user.getId()));
            entity.setName("账号" + (count + 1));
        }
        wmmtLoginStateMapper.insert(entity);
        log.info("歪麦登录态新增 id={}, userId={}, name={}", entity.getId(), user.getId(), entity.getName());
        return entity.getId();
    }

    @Override
    public List<WmmtLoginStateVO> list() {
        Integer uid = userService.getByCurrentRequest().getId();
        return wmmtLoginStateMapper.selectList(
                        new LambdaQueryWrapper<WmmtLoginStateEntity>()
                                .eq(WmmtLoginStateEntity::getUserId, uid)
                                .orderByDesc(WmmtLoginStateEntity::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    public void delete(Integer id) {
        Integer uid = userService.getByCurrentRequest().getId();
        WmmtLoginStateEntity entity = wmmtLoginStateMapper.selectById(id);
        if (entity == null || !entity.getUserId().equals(uid)) {
            throw new BusinessException("无权操作该歪麦登录态");
        }
        wmmtLoginStateMapper.deleteById(id);
        log.info("歪麦登录态删除 id={}, userId={}", id, uid);
    }

    @Override
    public WmmtLoginStateEntity getOwnedById(Integer id, Integer ownerId) {
        WmmtLoginStateEntity entity = wmmtLoginStateMapper.selectById(id);
        if (entity == null || !entity.getUserId().equals(ownerId)) {
            throw new BusinessException("所选歪麦账号不存在或无权使用: id=" + id);
        }
        return entity;
    }

    private WmmtLoginStateVO toVO(WmmtLoginStateEntity e) {
        WmmtLoginStateVO vo = new WmmtLoginStateVO();
        vo.setId(e.getId());
        vo.setName(e.getName());
        vo.setMaskedToken(maskToken(e.getToken()));
        vo.setCity(e.getCity());
        vo.setUpdateTime(e.getUpdateTime());
        return vo;
    }

    /** 掩码：前4 + **** + 后4；不足 8 位则只显 ****。不回显明文。 */
    private String maskToken(String token) {
        if (!StringUtils.hasText(token)) return null;
        if (token.length() <= 8) return "****";
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }
}