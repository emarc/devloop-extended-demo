package com.porotype.devloopdemo.ui;

import com.porotype.base.ui.ViewTitle;
import com.porotype.examplefeature.TaskService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Menu;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.time.LocalDate;
import java.util.List;

/**
 * A view with a deliberate bug, for trying the dev loop's error reporting:
 * clicking the button throws on the server, the daemon journals the exception
 * as {@code app.error}, and the next prompt to Claude is briefed about it.
 */
@Route("error-demo")
@PageTitle("Error Demo")
@Menu(order = 10, icon = "vaadin:bug", title = "Error Demo")
class ErrorDemoView extends VerticalLayout {

    private static final List<String> SAMPLE_TASKS = List.of(
            "Review the Copilot changes|2026-12-01",
            "Write the release notes|2026-12-15",
            "Plan the next sprint|2026-13-01");

    private final TaskService taskService;

    ErrorDemoView(TaskService taskService) {
        this.taskService = taskService;

        var loadSamples = new Button("Add sample tasks", event -> addSampleTasks());

        add(new ViewTitle("Error Demo"));
        add(new Paragraph("This button has a bug: it throws an exception on the server. "
                + "Click it, then send Claude any prompt, and the dev loop briefing will "
                + "mention the error with the line it came from."));
        add(loadSamples);
    }

    private void addSampleTasks() {
        for (String sample : SAMPLE_TASKS) {
            var parts = sample.split("\\|");
            taskService.createTask(parts[0], LocalDate.parse(parts[1]));
        }
        Notification.show("Sample tasks added", 3000, Notification.Position.BOTTOM_END);
    }
}
