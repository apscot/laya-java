package io.github.apscot.laya.spring;

import io.github.apscot.laya.api.ModelRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@EnableConfigurationProperties(LayaProperties.class)
public class LayaAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ModelRegistry layaModelRegistry(LayaProperties props) {
        Map<String, String> sources = new LinkedHashMap<>(ModelRegistry.defaults().sources());
        sources.putAll(props.getModels());
        return new ModelRegistry(sources, props.getDefaultModel());
    }

    @Bean
    public SmartInitializingSingleton layaPreload(ModelRegistry registry, LayaProperties props) {
        return () -> {
            if (props.isPreload()) registry.preload();
        };
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "laya.endpoint", name = "enabled", havingValue = "true", matchIfMissing = true)
    @Import(LayaController.class)
    static class Web {
        @Bean
        @ConditionalOnProperty(prefix = "laya", name = "api-key")
        FilterRegistrationBean<LayaApiKeyFilter> layaApiKeyFilter(LayaProperties props) {
            FilterRegistrationBean<LayaApiKeyFilter> r = new FilterRegistrationBean<>(new LayaApiKeyFilter(props.getApiKey()));
            r.addUrlPatterns("/*");
            return r;
        }
    }
}
