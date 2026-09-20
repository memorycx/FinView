package com.finview.mapper;

import com.finview.entity.DayPoint;
import com.finview.entity.Fund;
import com.finview.entity.FundSeriesPoint;
import com.finview.entity.PlanAdjustment;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface FundMapper {

    /**
     * 查询定投计划（不含 series / adjustments）。
     *
     * @param active null=全部；true=进行中；false=已归档
     */
    List<Fund> selectFunds(@Param("active") Boolean active);

    Fund selectFundById(@Param("id") String id);

    /** 单只计划的本金/市值走势（按日期正序） */
    List<DayPoint> selectSeries(@Param("fundId") String fundId);

    /** 单只计划的调整记录（按时间正序） */
    List<PlanAdjustment> selectAdjustments(@Param("fundId") String fundId);

    /** 全部进行中计划的走势点（带基金 id，供组合总览按年聚合） */
    List<FundSeriesPoint> selectActiveSeries();
}
