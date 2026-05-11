-- =============================================
-- V5: Remaining ENUM types for all schemas
-- All ENUMs live in schema 'system' as per spec
-- =============================================

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'payment_method' AND typnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'system')) THEN
        CREATE TYPE system.payment_method AS ENUM ('tunai', 'transfer', 'qris', 'hutang', 'dp');
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'trx_status' AND typnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'system')) THEN
        CREATE TYPE system.trx_status AS ENUM ('lunas', 'dp', 'hutang');
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'trx_type' AND typnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'system')) THEN
        CREATE TYPE system.trx_type AS ENUM ('penjualan', 'retur_masuk');
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'receivable_status' AND typnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'system')) THEN
        CREATE TYPE system.receivable_status AS ENUM ('belum_lunas', 'sebagian', 'lunas');
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'payable_status' AND typnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'system')) THEN
        CREATE TYPE system.payable_status AS ENUM ('belum_lunas', 'sebagian', 'lunas');
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'adj_type' AND typnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'system')) THEN
        CREATE TYPE system.adj_type AS ENUM ('opname', 'koreksi', 'retur_supplier');
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'audit_action' AND typnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'system')) THEN
        CREATE TYPE system.audit_action AS ENUM ('INSERT', 'UPDATE', 'DELETE');
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'printer_size' AND typnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'system')) THEN
        CREATE TYPE system.printer_size AS ENUM ('58mm', '80mm');
    END IF;
END $$;
