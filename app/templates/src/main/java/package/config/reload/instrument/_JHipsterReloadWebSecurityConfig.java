package <%=packageName%>.config.reload.instrument;

import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.ProxyFactory;
import javassist.util.proxy.ProxyObject;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.access.ConfigAttribute;
import org.springframework.security.access.annotation.Jsr250MethodSecurityMetadataSource;
import org.springframework.security.access.annotation.SecuredAnnotationSecurityMetadataSource;
import org.springframework.security.access.expression.method.ExpressionBasedAnnotationAttributeFactory;
import org.springframework.security.access.intercept.aopalliance.MethodSecurityMetadataSourceAdvisor;
import org.springframework.security.access.method.DelegatingMethodSecurityMetadataSource;
import org.springframework.security.access.method.MethodSecurityMetadataSource;
import org.springframework.security.access.prepost.PrePostAnnotationSecurityMetadataSource;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.method.configuration.GlobalMethodSecurityConfiguration;
import org.springsource.loaded.agent.SpringLoadedAgent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * By default, the DelegatingMethodSecurityMetadataSource keeps in cache the AOP data during the first call,
 * so even if the class is reloaded the cache is used and the previous AOP data are returned.
 *
 * This class will create a Proxy to change the behavior of the getAttributes method to remove the caching.
 */
@Configuration
@ConditionalOnClass(SpringLoadedAgent.class)
@EnableGlobalMethodSecurity
public class JHipsterReloadWebSecurityConfig extends GlobalMethodSecurityConfiguration {

    private final Logger log = LoggerFactory.getLogger(JHipsterReloadWebSecurityConfig.class);

    /**
     * Obtains the {@link org.springframework.security.access.intercept.aopalliance.MethodSecurityMetadataSourceAdvisor} to be used.
     *
     * @return
     */
    @Bean(name = "methodSecurityMetadataSourceAdvisor")
    public MethodSecurityMetadataSourceAdvisor metaDataSourceAdvisor() {
        MethodSecurityMetadataSourceAdvisor methodAdvisor = new MethodSecurityMetadataSourceAdvisor(
                "methodSecurityInterceptor", methodSecurityMetadataSource(),
                "methodSecurityMetadataSource");
        methodAdvisor.setOrder(Ordered.LOWEST_PRECEDENCE);
        return methodAdvisor;
    }

    /**
     * Used by method advice
     */
    @Bean(name = "delegatingMethodSecurityMetadataSource")
    public MethodSecurityMetadataSource methodSecurityMetadataSource() {
        ExpressionBasedAnnotationAttributeFactory attributeFactory = new ExpressionBasedAnnotationAttributeFactory(getExpressionHandler());

        final List<MethodSecurityMetadataSource> sources = new ArrayList<>();

        sources.add(new PrePostAnnotationSecurityMetadataSource(attributeFactory));
        sources.add(new SecuredAnnotationSecurityMetadataSource());
        sources.add(new Jsr250MethodSecurityMetadataSource());

        // Create a proxy to overwrite the getAttributes method
        try {
            ProxyFactory factory = new ProxyFactory();
            factory.setSuperclass(DelegatingMethodSecurityMetadataSource.class);
            Class clazz = factory.createClass();

            MethodHandler handler = new MethodHandler() {

                @Override
                public Object invoke(Object self, Method overridden, Method forwarder,
                                     Object[] args) throws Throwable {
                    
                    if (StringUtils.equals("getAttributes", overridden.getName())) {
                        Collection<ConfigAttribute> attributes = null;
                        for (MethodSecurityMetadataSource s : sources) {
                            attributes = s.getAttributes((Method) args[0], (Class) args[1]);
                            if (attributes != null && !attributes.isEmpty()) {
                                break;
                            }
                        }
                        return attributes;
                    }
                    return forwarder.invoke(self, args);
                }
            };

            final Constructor constructor = clazz.getConstructor(List.class);
            final Object delegatingMethodSecurityMetadataSource = constructor.newInstance(sources);
            ((ProxyObject) delegatingMethodSecurityMetadataSource).setHandler(handler);
            return (MethodSecurityMetadataSource) delegatingMethodSecurityMetadataSource;
        } catch (Exception e) {
            log.error("Failed to instantiate the DelegatingMethodSecurityMetadataSource class");
        }

        throw new IllegalStateException("Failed to instantiate the DelegatingMethodSecurityMetadataSource class");
    }
}
