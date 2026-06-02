-- V11: SECURITY DEFINER lookup for API-key authentication.
--
-- The authentication filter must find an API key by its prefix BEFORE any tenant context
-- exists, in order to discover which tenant the key belongs to. Under the runtime cauce_app
-- role that lookup would be filtered to nothing by the api_keys RLS policy (no
-- app.current_tenant_id set => api_key_is_visible returns false => fail-closed).
--
-- This SECURITY DEFINER function runs as the owner, which bypasses RLS (tables are ENABLE,
-- not FORCE), returning the active candidates regardless of context. Exposing prefix matches
-- is safe: the caller still verifies the bcrypt hash before trusting a row, and the prefix
-- alone authenticates nobody. Resolves the TODO on ApiKeyService.findActiveByKeyPrefix.
CREATE FUNCTION api_keys_active_by_prefix(p_key_prefix text)
    RETURNS SETOF api_keys
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT * FROM api_keys
    WHERE key_prefix = p_key_prefix AND revoked_at IS NULL;
$$;
