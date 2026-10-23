-- Dumps one tenant's audit chain in this verifier's input format (README, "Producing a dump").
-- psql "$DATABASE_URL" -v tenant=<tenant-uuid> -At -f dump.sql > dump.json
SELECT json_build_object(
  'format', 'cauce-audit-chain-dump/1',
  'tenant_id', :'tenant',
  'entries', COALESCE(json_agg(json_build_object(
      'id', id,
      'sequence_number', sequence_number,
      'outbox_id', outbox_id,
      'event_type', event_type,
      'payload', payload,
      'drained_at', to_char(drained_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
      'payload_hash', payload_hash,
      'prev_hash', prev_hash,
      'entry_hash', entry_hash,
      'hash_scheme', hash_scheme,
      'signature', signature,
      'key_id', key_id,
      'signature_scheme', signature_scheme
    ) ORDER BY sequence_number), '[]'::json)
)
FROM audit_log_entries
WHERE tenant_id = :'tenant';
