package de.caritas.cob.userservice.api.config.auth;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.HttpTenantFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${multitenancy.enabled:true}")
class TenantFilterRegistrationConfig {

  @Bean
  FilterRegistrationBean<HttpTenantFilter> disableHttpTenantFilterContainerRegistration(
      HttpTenantFilter tenantFilter) {
    var registration = new FilterRegistrationBean<>(tenantFilter);
    registration.setEnabled(false);
    return registration;
  }
}
