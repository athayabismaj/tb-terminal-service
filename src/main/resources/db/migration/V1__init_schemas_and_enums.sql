CREATE SCHEMA IF NOT EXISTS system;

-- Create ENUM types for system schema
CREATE TYPE system.user_role AS ENUM ('owner', 'admin', 'kasir');
