package com.finview.config;

import com.finview.service.TradingCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动预热行情数据。
 *
 * 目前只预热交易日历：它是唯一需要**同步**挡在业务路径上的外部数据
 * （推进 asset_series 前必须知道当天是不是交易日），启动时先异步拉好，
 * 第一次登录就不用等脚本了。失败不影响启动，登录时 {@code ensureLoaded} 会再兜一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketDataWarmup implements ApplicationRunner {

    private final TradingCalendarService tradingCalendarService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("启动预热：异步拉取交易日历");
        tradingCalendarService.refreshAsync();
    }
}
