-- =============================================
-- V48: Idempotency pembelian (barang masuk) dan pembayaran hutang supplier
-- =============================================
-- Key opsional (klien lama tidak mengirimnya), jadi kolom nullable dan unique index parsial.
-- Serialisasi request paralel memakai pg_advisory_xact_lock di repository; unique index adalah
-- pengaman terakhir bila lock itu terlewat.

ALTER TABLE purchasing.purchases
    ADD COLUMN idempotency_key     VARCHAR(100),
    ADD COLUMN request_fingerprint CHAR(64),
    ADD CONSTRAINT ck_purchasing_purchases_idempotency_pair
        CHECK ((idempotency_key IS NULL) = (request_fingerprint IS NULL));

CREATE UNIQUE INDEX uq_purchasing_purchases_idempotency
    ON purchasing.purchases (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- payable_remaining_after/payable_status_after: snapshot hutang tepat setelah pembayaran ini, agar
-- retry bisa mengembalikan respons pertama apa adanya walau hutangnya sudah dibayar lagi. Baris lama NULL.
ALTER TABLE purchasing.supplier_payments
    ADD COLUMN idempotency_key         VARCHAR(100),
    ADD COLUMN request_fingerprint     CHAR(64),
    ADD COLUMN payable_remaining_after NUMERIC(15,2),
    ADD COLUMN payable_status_after    system.payable_status,
    ADD CONSTRAINT ck_purchasing_supplier_payments_idempotency_pair
        CHECK ((idempotency_key IS NULL) = (request_fingerprint IS NULL)),
    ADD CONSTRAINT ck_purchasing_supplier_payments_after_pair
        CHECK ((payable_remaining_after IS NULL) = (payable_status_after IS NULL)),
    ADD CONSTRAINT ck_purchasing_supplier_payments_remaining_after_nonnegative
        CHECK (payable_remaining_after IS NULL OR payable_remaining_after >= 0);

CREATE UNIQUE INDEX uq_purchasing_supplier_payments_idempotency
    ON purchasing.supplier_payments (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
