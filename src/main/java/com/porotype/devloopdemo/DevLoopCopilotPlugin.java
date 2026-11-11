package com.porotype.devloopdemo;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import org.springframework.stereotype.Component;

/**
 * Adds the dev loop's "Claude" panel to Copilot: a script the daemon serves,
 * whose URL it passes as {@code vaadin.devloop.copilotPlugin}. Without the
 * daemon (a production build, {@code mvn spring-boot:run}) the property is
 * unset and this does nothing.
 */
@Component
class DevLoopCopilotPlugin implements VaadinServiceInitListener {

    @Override
    public void serviceInit(ServiceInitEvent event) {
        var url = System.getProperty("vaadin.devloop.copilotPlugin");
        if (url == null || event.getSource().getDeploymentConfiguration().isProductionMode()) {
            return;
        }
        event.addIndexHtmlRequestListener(response -> response.getDocument().head()
                .appendElement("script").attr("type", "module").attr("src", url));
    }
}
