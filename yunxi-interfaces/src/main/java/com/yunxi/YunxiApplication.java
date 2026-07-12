package com.yunxi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.yunxi.infrastructure.persistence.mapper")
public class YunxiApplication {
    public static void main(String[] args) {
        SpringApplication.run(YunxiApplication.class, args);
    }
}
