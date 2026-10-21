package dev.cauce.core.tool;

/**
 * Port: the names of the tools this instance can execute. Implemented by the cauce-tools
 * registry; consumed where a module that does not depend on cauce-tools must validate tool
 * names — granting tools to an agent (ADR 0005, C4) is validated against it.
 */
public interface ToolCatalog {

    /** Whether a tool named {@code toolName} is registered. */
    boolean contains(String toolName);
}
