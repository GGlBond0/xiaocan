package io.github.xiaocan.service;

import io.github.xiaocan.model.StoreInfo;
import io.github.xiaocan.model.dto.XcMeituanshangjinDTO;
import io.github.xiaocan.model.vo.QueryListVO;
import io.github.xiaocan.model.vo.XcMeituanshangjinPageVO;

import java.util.List;


public interface XiaoChanService {


    /**
     * 获取列表
     *
     * @param cityCode cityCode
     * @param longitude 经度
     * @param latitude 纬度
     * @param maxSize  最大数量，因为小蚕并不是按照距离排序返回的，有时候中间会穿插一些距离比较远的活动，所以这里限制一下数量
     * @return 列表
     */
    List<StoreInfo> getList(Integer cityCode, String longitude, String latitude, int maxSize);

    /**
     * 获取列表、分页方式
     *
     * @param cityCode cityCode
     * @param longitude 经度
     * @param latitude 纬度
     * @param offset  offset
     * @return 列表
     */
    List<StoreInfo> getListByOffset(Integer cityCode, String longitude, String latitude, int offset);

    /**
     * 获取列表
     * <p>
     * 只返回15个结果、不分页
     * @param keyword   关键字
     * @param cityCode  cityCode
     * @param longitude 经度
     * @param latitude  纬度
     * @return 列表
     */
    List<StoreInfo> searchList(String keyword, Integer cityCode, String longitude, String latitude);




    /**
     * 查询
     * @param queryListVO
     * @return
     */

    List<StoreInfo> query(QueryListVO queryListVO);

    /**
     * 获取小蚕美团赏金门店列表（支持关键词搜索与翻页游标 pvId）
     *
     * @param dto 请求参数（name 非空走搜索接口，否则走全量列表接口）
     * @return 美团赏金门店列表 + 翻页游标
     */
    XcMeituanshangjinPageVO getXcMeituanshangjinPageVO(XcMeituanshangjinDTO dto);
}
