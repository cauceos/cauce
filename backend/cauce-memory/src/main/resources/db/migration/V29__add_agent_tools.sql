-- V29: the tools an agent may use (ADR 0005, C4).
--
-- An explicit list of tool names per agent, empty by default: not even the clock. Every
-- existing agent therefore loses tool access with this migration, deliberately, and regains it
-- only when granted. The names are validated against the tool registry by the service when the
-- list is set; the database does not know which tools a deployment registers (the registry is
-- code), so it checks only the shape: no NULL element. A plain column, not a table: the list is
-- configuration of the agent, read with it and visible exactly when it is (the agents RLS).

ALTER TABLE agents ADD COLUMN tool_names TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE agents ADD CONSTRAINT agents_tool_names_shape
    CHECK (array_position(tool_names, NULL) IS NULL);
