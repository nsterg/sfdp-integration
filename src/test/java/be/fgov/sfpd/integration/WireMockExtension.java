package be.fgov.sfpd.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.Options;
import org.junit.jupiter.api.extension.*;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import java.lang.reflect.Method;

public class WireMockExtension implements InvocationInterceptor, ParameterResolver {

    private final WireMockServer server;

    public WireMockExtension(Options options) {
        server = new WireMockServer(options);
    }

    public WireMockExtension() {
        server = new WireMockServer();
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        server.start();
        try {
            invocation.proceed();
        } finally {
            server.stop();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return parameterContext.getParameter().getType().equals(WireMockServer.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return server;
    }

    public void stubPostWithError(String url) {
    	server.stubFor(WireMock.post(WireMock.urlEqualTo(url))
	            .willReturn(WireMock.aResponse()
	                .withStatus(500)));
    }

    public void verifyPost(String url) {
    	server.verify(postRequestedFor(WireMock.urlEqualTo(url)));
    }
}
