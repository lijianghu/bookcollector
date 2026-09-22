package com.bookcollector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 微信读书图书采集后台 · 启动类
 *
 * <p>Spring Boot 2.3.12.RELEASE + JDK 8
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class BookCollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookCollectorApplication.class, args);
    }
}
