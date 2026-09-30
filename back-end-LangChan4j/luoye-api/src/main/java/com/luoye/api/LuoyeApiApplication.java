package com.luoye.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 落叶（LuoYe）后端应用入口。
 * 启动 Spring 容器与 Web 服务，装配 API、Service、DAO 和 Common 模块中的组件。
 */
// 扫描配置属性类；@MapperScan 统一注册 dao 模块的 MyBatis Mapper。
@ConfigurationPropertiesScan("com.luoye")
@MapperScan("com.luoye.dao.mapper")
// 指定共同根包，才能发现与 api 平级的 service、dao 中的 Spring 组件。
@SpringBootApplication(scanBasePackages = "com.luoye")
public class LuoyeApiApplication {

    /** 接收启动参数并交给 Spring Boot，依次加载配置、创建依赖并启动 HTTP 服务。 */
    public static void main(String[] args) {
        SpringApplication.run(LuoyeApiApplication.class, args);
    }
}
