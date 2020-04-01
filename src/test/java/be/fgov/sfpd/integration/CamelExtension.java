package be.fgov.sfpd.integration;

import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.properties.PropertiesComponent;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.Method;
import java.util.Properties;

public class CamelExtension implements InvocationInterceptor {

    private final RouteBuilder[] builders;
    private final Properties properties = new Properties();

    public CamelExtension(RouteBuilder... builders) {
        this.builders = builders;
    }

    public void setProperty(String key, String value) {
        properties.put(key, value);
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        CamelContext context = new DefaultCamelContext();
        PropertiesComponent component = new PropertiesComponent();
        component.setOverrideProperties(properties);
        context.addComponent("properties", component);
        for (RouteBuilder builder : builders) {
            context.addRoutes(builder);
        }
        context.start();
        try {
            invocation.proceed();
        } finally {
            context.stop();
        }
    }
}
