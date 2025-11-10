-- Trigger: log message insertions into audit_log
CREATE OR REPLACE FUNCTION log_message_insert()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO audit_log(event_type, event_data)
    VALUES ('MESSAGE_CREATED', NEW.content);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger only if it does not already exist
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname = 'trg_log_message_insert'
    ) THEN
        CREATE TRIGGER trg_log_message_insert
        AFTER INSERT ON messages
        FOR EACH ROW
        EXECUTE FUNCTION log_message_insert();
    END IF;
END;
$$;