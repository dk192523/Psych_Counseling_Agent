package com.dk.dkaiagent.config;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.session.StandardManager;
import org.apache.catalina.startup.Tomcat;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Set;

/** One Tomcat connector, two contexts: static Vue at / and the unchanged secured API at /api. */
@Configuration(proxyBeanMethods = false)
@Profile("desktop")
public class DesktopWebConfig {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> desktopWebServer() {
        return factory -> {
            factory.setAddress(InetAddress.getLoopbackAddress());
            factory.setPort(0);
            factory.setContextPath("/api");
            factory.addContextCustomizers(apiContext -> {
                if (DesktopWebConfig.class.getResource("/static/index.html") == null) {
                    throw new IllegalStateException("Desktop frontend is missing; build with Maven -Pdesktop");
                }
                StandardContext ui = new StandardContext();
                ui.setName("");
                ui.setPath("");
                ui.setDocBase(apiContext.getDocBase());
                ui.setParentClassLoader(DesktopWebConfig.class.getClassLoader());
                ui.addLifecycleListener(new Tomcat.FixContextListener());
                StandardManager manager = new StandardManager();
                manager.setPathname(null); // No root-context sessions or SESSIONS.ser in the install directory.
                ui.setManager(manager);
                Tomcat.addServlet(ui, "desktop-ui", new VueStaticServlet()).setLoadOnStartup(1);
                ui.addServletMappingDecoded("/", "desktop-ui");
                apiContext.getParent().addChild(ui);
            });
            // Enforce IPv4 explicitly: WebView loads 127.0.0.1, never an IPv6-only binding.
            factory.addConnectorCustomizers(connector -> connector.setProperty("address", "127.0.0.1"));
        };
    }

    private static final class VueStaticServlet extends HttpServlet {
        private static final Set<String> ROUTES = Set.of("/", "/login", "/psych-master", "/practice", "/admin");

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String path = request.getServletPath();
            if (path == null || path.indexOf('\\') >= 0 || path.contains("..") || path.indexOf('\0') >= 0
                    || path.indexOf('%') >= 0 || path.startsWith("/api")) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            String resource = ROUTES.contains(path) ? "/index.html" : path;
            // Only built frontend resources are served, never arbitrary classpath/configuration files.
            try (InputStream stream = DesktopWebConfig.class.getResourceAsStream("/static" + resource)) {
                if (stream == null) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                String mime = getServletContext().getMimeType(resource);
                if (resource.endsWith(".js") || resource.endsWith(".mjs")) mime = "text/javascript";
                if (resource.endsWith(".svg")) mime = "image/svg+xml";
                response.setContentType(mime == null ? "application/octet-stream" : mime);
                response.setCharacterEncoding("UTF-8");
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("Referrer-Policy", "no-referrer");
                response.setHeader("Cache-Control", resource.equals("/index.html") ? "no-store" : "no-cache");
                stream.transferTo(response.getOutputStream());
            }
        }
    }
}
