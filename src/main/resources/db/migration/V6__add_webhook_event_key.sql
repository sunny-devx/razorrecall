ALTER TABLE webhook_events
ADD COLUMN event_key VARCHAR(255);

UPDATE webhook_events
SET event_key = event_type || ':' || (payload::json ->> 'payment_id')
WHERE event_type IS NOT NULL
  AND payload IS NOT NULL
  AND payload::json ->> 'payment_id' IS NOT NULL;

CREATE UNIQUE INDEX ux_webhook_events_event_key
ON webhook_events(event_key)
WHERE event_key IS NOT NULL;