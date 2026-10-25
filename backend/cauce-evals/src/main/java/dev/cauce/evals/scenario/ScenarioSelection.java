package dev.cauce.evals.scenario;

import java.util.List;

/**
 * The scenarios a run is asked for ({@code -Pevals.tarea}, {@code -Pevals.escenarios}). A scenario id
 * or a task that matches nothing is an error: left out silently, the run would measure less than was
 * asked and say nothing.
 */
public final class ScenarioSelection {

    private ScenarioSelection() {
    }

    /**
     * The scenarios of {@code all} in the given tasks (by prefix of the task file) and with the given
     * ids; an empty list selects every one.
     *
     * @throws InvalidScenarioException if an id is no scenario's or a task prefix no task's
     */
    public static List<Scenario> select(List<Scenario> all, List<String> tasks, List<String> scenarioIds) {
        List<String> unknownIds = scenarioIds.stream()
                .filter(id -> all.stream().noneMatch(s -> s.id().equals(id))).toList();
        List<String> unknownTasks = tasks.stream()
                .filter(task -> all.stream().noneMatch(s -> s.task().startsWith(task))).toList();
        if (!unknownIds.isEmpty() || !unknownTasks.isEmpty()) {
            StringBuilder message = new StringBuilder("Selección con lo que no existe:");
            if (!unknownIds.isEmpty()) {
                message.append(" escenarios ").append(String.join(", ", unknownIds)).append(';');
            }
            if (!unknownTasks.isEmpty()) {
                message.append(" tareas ").append(String.join(", ", unknownTasks)).append(';');
            }
            message.append(" ¿el árbol está al día? Tareas: ")
                    .append(String.join(", ", all.stream().map(Scenario::task).distinct().toList()));
            throw new InvalidScenarioException(message.toString());
        }
        return all.stream()
                .filter(s -> tasks.isEmpty() || tasks.stream().anyMatch(s.task()::startsWith))
                .filter(s -> scenarioIds.isEmpty() || scenarioIds.contains(s.id()))
                .toList();
    }
}
