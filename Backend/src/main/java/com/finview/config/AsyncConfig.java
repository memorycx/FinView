package com.finview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 行情数据抓取的线程池：净值（NavService）与交易日历（TradingCalendarService）共用。
 *
 * 单独给一个**单线程**执行器而不是用 @Async 默认池，原因有四：
 * - 抓取要起 python 进程、访问外部接口，串行执行对第三方最友好，也不会一次占满连接；
 * - 默认池是 Spring Boot 自动配置的 applicationTaskExecutor，按类型注入会撞车，所以这里显式取名；
 * - 两类抓取互不阻塞：日历只在启动时/跨天后跑一次，净值在登录后跑，排队也不会互相拖；
 * - 抓取是尽力而为的增强功能，线程设为 daemon：服务关闭时不该等它。
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "navRefreshExecutor", destroyMethod = "shutdown")
    public Executor navRefreshExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "nav-refresh");
            thread.setDaemon(true);
            return thread;
        });
    }
}
