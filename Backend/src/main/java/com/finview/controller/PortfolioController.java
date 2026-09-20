package com.finview.controller;

import com.finview.common.Result;
import com.finview.entity.DayPoint;
import com.finview.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioService portfolioService;

    /** GET /portfolio/series */
    @GetMapping("/series")
    public Result<List<DayPoint>> series() {
        return Result.success(portfolioService.yearlySeries());
    }
}
