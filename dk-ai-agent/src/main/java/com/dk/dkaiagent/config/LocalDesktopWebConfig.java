package com.dk.dkaiagent.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.session.StandardManager;
import org.apache.catalina.startup.Tomcat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Loopback-only desktop API with an optional same-origin UI context. */
@Configuration(proxyBeanMethods = false)
@Profile("local-desktop")
public class LocalDesktopWebConfig {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> localDesktopWebServer(
            @Value("${app.desktop.serve-ui:true}") boolean serveUi, ResourceLoader resourceLoader) {
        if (serveUi && !resourceLoader.getResource("classpath:/static/index.html").exists()) {
            throw new IllegalStateException("The local-desktop profile requires the built Vue frontend "
                    + "packaged at classpath:/static/index.html; package the frontend before starting.");
        }
        return factory -> {
            try {
                // Explicit IPv4 matches desktop clients that open http://127.0.0.1:<port>/.
                factory.setAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            } catch (UnknownHostException exception) {
                throw new IllegalStateException("Cannot configure the desktop loopback address", exception);
            }
            factory.setContextPath("/api");
            factory.addConnectorCustomizers(connector -> connector.setProperty("address", "127.0.0.1"));
            if (!serveUi) {
                return;
            }
            factory.addContextCustomizers(apiContext -> {
                StandardContext uiContext = new StandardContext();
                uiContext.setName("");
                uiContext.setPath("");
                uiContext.setDocBase(apiContext.getDocBase());
                uiContext.setParentClassLoader(LocalDesktopWebConfig.class.getClassLoader());
                uiContext.addLifecycleListener(new Tomcat.FixContextListener());
                StandardManager manager = new StandardManager();
                manager.setPathname(null);
                uiContext.setManager(manager);
                // This servlet never creates sessions; the API keeps its own session manager and filters.
                Tomcat.addServlet(uiContext, "local-desktop-ui", new VueStaticServlet()).setLoadOnStartup(1);
                uiContext.addServletMappingDecoded("/", "local-desktop-ui");
                apiContext.getParent().addChild(uiContext);
            });
        };
    }

    private static final class VueStaticServlet extends HttpServlet {
        private static final Set<String> CLIENT_ROUTES =
                Set.of("/", "/login", "/psych-master", "/practice", "/admin");
        private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self' data:; "
                + "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            // Match the existing frontend security-headers.conf, including errors and unsupported methods.
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
            response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
            response.setHeader("Cache-Control", "no-store");
            String path = request.getServletPath();
            // Check raw URI as well: Tomcat may already have normalized decoded dot segments.
            if (!isSafePath(request.getRequestURI()) || !isSafePath(path)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
                response.setHeader("Allow", "GET, HEAD");
                response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                return;
            }
            super.service(request, response);
        }

        private static boolean isSafePath(String path) {
            if (path == null || !path.startsWith("/") || path.startsWith("/api")
                    || path.contains("..") || path.contains("//") || path.indexOf('\\') >= 0
                    || path.indexOf('%') >= 0 || path.indexOf(';') >= 0) {
                return false;
            }
            for (String segment : path.split("/")) {
                if (segment.startsWith(".") || segment.equalsIgnoreCase("WEB-INF")
                        || segment.equalsIgnoreCase("META-INF")) {
                    return false;
                }
            }
            return path.chars().noneMatch(character -> Character.isISOControl(character));
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String path = request.getServletPath();
            String resource = CLIENT_ROUTES.contains(path) ? "/index.html" : path;
            // Resolve exclusively inside packaged frontend resources, never from the working directory.
            try (InputStream stream = LocalDesktopWebConfig.class.getResourceAsStream("/static" + resource)) {
                if (stream == null || resource.endsWith("/")) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                String mime = getServletContext().getMimeType(resource);
                // The standalone UI context does not inherit Tomcat's default MIME mappings.
                if (resource.endsWith(".html")) {
                    mime = "text/html";
                } else if (resource.endsWith(".css")) {
                    mime = "text/css";
                } else if (resource.endsWith(".js") || resource.endsWith(".mjs")) {
                    mime = "text/javascript";
                } else if (resource.endsWith(".png")) {
                    mime = "image/png";
                } else if (resource.endsWith(".svg")) {
                    mime = "image/svg+xml";
                }
                response.setContentType(mime == null ? "application/octet-stream" : mime);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.setHeader("Cache-Control", resource.equals("/index.html") ? "no-store" : "no-cache");
                stream.transferTo(response.getOutputStream());
            }
        }
    }
}
