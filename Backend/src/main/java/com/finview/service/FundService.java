package com.finview.service;

import com.finview.common.BusinessException;
import com.finview.entity.Fund;
import com.finview.mapper.FundMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FundService {

    private final FundMapper fundMapper;

    /**
     * 定投计划列表。
     *
     * @param status active=进行中，archived=已归档，null/其他=全部
     */
    public List<Fund> list(String status) {
        Boolean active = parseStatus(status);
        List<Fund> funds = fundMapper.selectFunds(active);
        funds.forEach(this::fillDetail);
        return funds;
    }

    /** 单个定投计划详情，不存在时抛 404 */
    public Fund getById(String id) {
        Fund fund = fundMapper.selectFundById(id);
        if (fund == null) {
            throw BusinessException.notFound("定投计划不存在: " + id);
        }
        fillDetail(fund);
        return fund;
    }

    /** 收益排行榜：含已归档，按收益率 (current - principal) / principal 降序 */
    public List<Fund> leaderboard() {
        List<Fund> funds = fundMapper.selectFunds(null);
        funds.forEach(this::fillDetail);
        funds.sort(Comparator.comparingDouble(this::returnRate).reversed());
        return funds;
    }

    private Boolean parseStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "active" -> Boolean.TRUE;
            case "archived" -> Boolean.FALSE;
            default -> null; // all 及未知名额均按全部处理，与 mock 行为一致
        };
    }

    private double returnRate(Fund fund) {
        if (fund.getPrincipal() == null || fund.getPrincipal() == 0) {
            return 0d;
        }
        return (fund.getCurrent() - fund.getPrincipal()) / (double) fund.getPrincipal();
    }

    private void fillDetail(Fund fund) {
        fund.setSeries(fundMapper.selectSeries(fund.getId()));
        fund.setAdjustments(fundMapper.selectAdjustments(fund.getId()));
    }
}
