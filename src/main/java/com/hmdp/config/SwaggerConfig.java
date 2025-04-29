package com.hmdp.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

@Configuration // 标明是配置类
@EnableSwagger2// 开启Swagger2的自动配置
public class SwaggerConfig {

    @Bean
    public Docket createRestApi() {
        return new Docket(DocumentationType.SWAGGER_2) // 使用OpenAPI 3.0规范
                .apiInfo(apiInfo()) // 配置API信息
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.hmdp.controller")) // 指定扫描的包路径
                .paths(PathSelectors.any()) // 指定扫描的路径
                .build();
    }

    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("API文档标题") // 文档标题
                .description("API文档描述") // 文档描述
                .contact(new springfox.documentation.service.Contact("作者", "作者网址", "作者邮箱")) // 文档作者信息
                .version("1.0.0") // 文档版本
                .build();
    }
}
