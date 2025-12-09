-- Add new column safely if it doesn’t exist
ALTER TABLE users ADD COLUMN last_login TIMESTAMP DEFAULT NULL;