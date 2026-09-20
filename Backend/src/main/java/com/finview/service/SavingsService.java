package com.finview.service;

import com.finview.common.BusinessException;
import com.finview.entity.SavingsPlan;
import com.finview.mapper.SavingsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SavingsService {

    private final SavingsMapper savingsMapper;

    public List<SavingsPlan> list() {
        List<SavingsPlan> plans = savingsMapper.selectPlans();
        plans.forEach(this::fillDetail);
        return plans;
    }

    public SavingsPlan getById(String id) {
        SavingsPlan plan = savingsMapper.selectPlanById(id);
        if (plan == null) {
            throw BusinessException.notFound("存钱计划不存在: " + id);
        }
        fillDetail(plan);
        return plan;
    }

    private void fillDetail(SavingsPlan plan) {
        plan.setAssets(savingsMapper.selectAssets(plan.getId()));
        plan.setSeries(savingsMapper.selectSeries(plan.getId()));
    }
}
