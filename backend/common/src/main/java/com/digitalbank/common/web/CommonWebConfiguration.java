package com.digitalbank.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 仅由 Servlet 业务服务显式导入；WebFlux 网关不加载此配置。 */
@Configuration(proxyBeanMethods = false)
@Import({TraceFilter.class, GlobalExceptionHandler.class, AuthWebConfiguration.class})
public class CommonWebConfiguration { }
