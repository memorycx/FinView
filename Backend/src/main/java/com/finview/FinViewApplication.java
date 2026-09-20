package com.finview;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.finview.mapper")
public class FinViewApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinViewApplication.class, args);
    }
}
