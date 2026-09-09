package com.yunxi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling          // ← 开启定时任务
@MapperScan("com.yunxi.infrastructure.persistence.mapper")
public class YunxiApplication {
    public static void main(String[] args) {
        SpringApplication.run(YunxiApplication.class, args);
    }
}
