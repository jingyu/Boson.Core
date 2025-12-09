-- Trigger: log message insertions into audit_log
CREATE TRIGGER IF NOT EXISTS trg_log_message_insert
AFTER INSERT ON messages
BEGIN
    INSERT INTO audit_log(event_type, event_data)
    VALUES ('MESSAGE_CREATED', NEW.content);
END;