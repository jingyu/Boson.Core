-- Create a view combining users and messages
DROP VIEW IF EXISTS view_user_messages;

CREATE VIEW view_user_messages AS
SELECT
    u.id AS user_id,
    u.name AS username,
    m.id AS message_id,
    m.content,
    m.created_at
FROM users u
JOIN messages m ON u.id = m.user_id;