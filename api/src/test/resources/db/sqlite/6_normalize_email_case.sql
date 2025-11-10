-- Normalize user emails to lowercase
UPDATE users
SET email = LOWER(email)
WHERE email != LOWER(email);