package com.luoye.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 落叶（LuoYe）后端应用入口。
 * 仅搭建骨架，不包含业务逻辑；业务在 M1 起逐步接入。
 */
@ConfigurationPropertiesScan("com.luoye")
@EntityScan("com.luoye.dao.entity")
@EnableJpaRepositories("com.luoye.dao.repository")
@SpringBootApplication(scanBasePackages = "com.luoye")
public class LuoyeApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LuoyeApiApplication.class, args);
    }
}