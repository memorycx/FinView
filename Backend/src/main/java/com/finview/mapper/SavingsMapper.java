package com.finview.mapper;

import com.finview.entity.SavingsAsset;
import com.finview.entity.SavingsMonthPoint;
import com.finview.entity.SavingsPlan;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SavingsMapper {

    List<SavingsPlan> selectPlans();

    SavingsPlan selectPlanById(@Param("id") String id);

    /** 计划内持有的资产（按 sort_order 正序） */
    List<SavingsAsset> selectAssets(@Param("planId") String planId);

    /** 月度存入走势（按月份正序） */
    List<SavingsMonthPoint> selectSeries(@Param("planId") String planId);
}
