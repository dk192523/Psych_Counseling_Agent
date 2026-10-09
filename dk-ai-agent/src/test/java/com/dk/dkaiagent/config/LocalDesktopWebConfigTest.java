package com.dk.dkaiagent.config;

import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class LocalDesktopWebConfigTest {

    private final DefaultResourceLoader resourceLoader = spy(new DefaultResourceLoader());
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> {
                context.getEnvironment().setActiveProfiles("local-desktop");
                ((GenericApplicationContext) context).setResourceLoader(resourceLoader);
            })
            .withUserConfiguration(LocalDesktopWebConfig.class);

    @Test
    void defaultConfigurationRequiresPackagedFrontend() {
        doReturn(mock(Resource.class)).when(resourceLoader).getResource("classpath:/static/index.html");

        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("requires the built Vue frontend");
        });
    }

    @Test
    void defaultConfigurationKeepsSameOriginUiAndApiContexts() {
        doReturn(new ByteArrayResource("<html></html>".getBytes(StandardCharsets.UTF_8)))
                .when(resourceLoader).getResource("classpath:/static/index.html");

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            TomcatServletWebServerFactory factory = customizedFactory(context.getBean(
                    "localDesktopWebServer", WebServerFactoryCustomizer.class));
            assertLoopbackApi(factory);

            StandardHost host = new StandardHost();
            host.setName("localhost");
            StandardContext apiContext = new StandardContext();
            apiContext.setName("/api");
            apiContext.setPath("/api");
            apiContext.setDocBase(".");
            host.addChild(apiContext);
            factory.getTomcatContextCustomizers().forEach(customizer -> customizer.customize(apiContext));

            assertThat(host.findChildren()).hasSize(2);
            StandardContext uiContext = (StandardContext) host.findChild("");
            assertThat(uiContext).isNotNull();
            assertThat(uiContext.getPath()).isEmpty();
            assertThat(uiContext.findServletMapping("/")).isEqualTo("local-desktop-ui");
        });
    }

    @Test
    void apiOnlyStartsWithoutFrontendAndRegistersNoUiContext() {
        // Leave the resource loader unstubbed: API-only must never inspect the frontend resource.
        contextRunner.withPropertyValues("app.desktop.serve-ui=false").run(context -> {
            assertThat(context).hasNotFailed();
            TomcatServletWebServerFactory factory = customizedFactory(context.getBean(
                    "localDesktopWebServer", WebServerFactoryCustomizer.class));

            assertLoopbackApi(factory);
            assertThat(factory.getTomcatContextCustomizers()).isEmpty();
            verify(resourceLoader, never()).getResource("classpath:/static/index.html");
        });
    }

    private static TomcatServletWebServerFactory customizedFactory(
            WebServerFactoryCustomizer<TomcatServletWebServerFactory> customizer) {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        customizer.customize(factory);
        return factory;
    }

    private static void assertLoopbackApi(TomcatServletWebServerFactory factory) {
        assertThat(factory.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
        assertThat(factory.getContextPath()).isEqualTo("/api");
        Connector connector = new Connector();
        factory.getTomcatConnectorCustomizers().forEach(customizer -> customizer.customize(connector));
        assertThat(((InetAddress) connector.getProperty("address")).getHostAddress()).isEqualTo("127.0.0.1");
    }
}
