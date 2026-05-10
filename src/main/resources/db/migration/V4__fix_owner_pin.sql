-- Update pin_hash for user 'owner' to match '123456'
UPDATE system.users 
SET pin_hash = '$2a$12$bLnNxkF4oJBf8qoymZ46xu3RMg.1o0jYQh/3A9x9XuCv8cl.hqDa6'
WHERE username = 'owner';
