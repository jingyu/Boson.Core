-- Insert some test data
INSERT INTO users (name, email)
SELECT 'Alice', 'alice@example.com'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'alice@example.com');

INSERT INTO users (name, email)
SELECT 'Bob', 'bob@example.com'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'bob@example.com');

INSERT INTO messages (user_id, content)
SELECT u.id, 'Hello from ' || u.name FROM users u
WHERE NOT EXISTS (SELECT 1 FROM messages WHERE content LIKE 'Hello from%');