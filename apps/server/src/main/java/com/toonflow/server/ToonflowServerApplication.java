package com.toonflow.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.toonflow.server.mapper")
@EnableScheduling
public class ToonflowServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ToonflowServerApplication.class, args);
    }
}
