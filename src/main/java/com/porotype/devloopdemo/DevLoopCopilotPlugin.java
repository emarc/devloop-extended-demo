package com.porotype.devloopdemo;

import com.vaadin.flow.server.VaadinServiceInitListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Adds the dev loop's "Claude" panel to Copilot. The daemon serves the panel
 * and its endpoints on a loopback port and passes their URL as
 * {@code vaadin.devloop.copilotPlugin}; a browser outside the machine (a
 * container, a remote host) can't reach that port, so the app forwards
 * {@code /devloop/*} to it and the page only talks to the app. Without the
 * daemon (a production build, {@code mvn spring-boot:run}) the property is
 * unset and none of this is registered.
 */
@Configuration
class DevLoopCopilotPlugin {

    private static final String PATH = "/devloop/";

    @Bean
    VaadinServiceInitListener devLoopCopilotPluginScript() {
        return event -> {
            if (daemonUrl() == null || event.getSource().getDeploymentConfiguration().isProductionMode()) {
                return;
            }
            event.addIndexHtmlRequestListener(response -> response.getDocument().head()
                    .appendElement("script").attr("type", "module")
                    .attr("src", response.getVaadinRequest().getContextPath() + PATH + "copilot-plugin.js"));
        };
    }

    @Bean
    ServletRegistrationBean<HttpServlet> devLoopCopilotPluginForwarder() {
        var url = daemonUrl();
        var registration = new ServletRegistrationBean<HttpServlet>(new DaemonForwarder(url), PATH + "*");
        registration.setEnabled(url != null);
        return registration;
    }

    /** The daemon's base URL for the panel, ending in /devloop/; null without a daemon. */
    private static String daemonUrl() {
        var plugin = System.getProperty("vaadin.devloop.copilotPlugin");
        return plugin == null ? null : plugin.substring(0, plugin.lastIndexOf('/') + 1);
    }

    private static class DaemonForwarder extends HttpServlet {

        private final String base;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

        DaemonForwarder(String base) {
            this.base = base;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
            var path = request.getPathInfo() == null ? "" : request.getPathInfo().substring(1);
            var forward = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                    .method(request.getMethod(), HttpRequest.BodyPublishers.ofByteArray(
                            request.getInputStream().readAllBytes()));
            // What is posted here reaches Claude's context, so only the app's own
            // pages may post. The daemon in turn only takes loopback origins, and
            // this app is its loopback client.
            var origin = request.getHeader("Origin");
            if (origin != null && !Objects.equals(URI.create(origin).getAuthority(), request.getHeader("Host"))) {
                response.sendError(403, "cross-origin request");
                return;
            }
            forward.header("Origin", "http://localhost");
            if (request.getContentType() != null) {
                forward.header("Content-Type", request.getContentType());
            }
            try {
                var reply = http.send(forward.build(), HttpResponse.BodyHandlers.ofByteArray());
                response.setStatus(reply.statusCode());
                reply.headers().firstValue("Content-Type").ifPresent(response::setContentType);
                response.setHeader("Cache-Control", "no-store");
                response.getOutputStream().write(reply.body());
            } catch (IOException e) {
                response.sendError(502, "dev-loop daemon unreachable: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                response.sendError(503);
            }
        }
    }
}
