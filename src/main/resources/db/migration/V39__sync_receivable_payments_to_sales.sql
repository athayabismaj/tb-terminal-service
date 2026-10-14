-- =============================================
-- V39: Sinkronisasi status, nominal terbayar, dan pencatatan pembayaran transaksi dari pelunasan piutang
-- Memastikan integritas data transaksi penjualan yang telah dilunasi lewat modul Piutang Pelanggan
-- =============================================

-- 1. Update sales.transactions yang memiliki selisih dengan data piutang (kecuali transaksi void)
UPDATE sales.transactions t
SET paid_amount = LEAST(t.total, GREATEST(0, t.total - (r.amount - r.paid_amount))),
    status = CASE
        WHEN (r.amount - r.paid_amount) <= 0 THEN 'lunas'::system.trx_status
        WHEN (t.total - (r.amount - r.paid_amount)) > 0 THEN 'dp'::system.trx_status
        ELSE t.status
    END
FROM receivable.receivables r
WHERE r.transaction_id = t.id
  AND r.is_active = true
  AND t.status != 'voided'::system.trx_status
  AND (
      t.paid_amount != LEAST(t.total, GREATEST(0, t.total - (r.amount - r.paid_amount)))
      OR (
          (r.amount - r.paid_amount) <= 0 AND t.status != 'lunas'::system.trx_status
      )
  );

-- 2. Sinkronkan riwayat pembayaran dari receivable.receivable_payments ke sales.payments
-- jika pembayaran tersebut belum tercatat di sales.payments
INSERT INTO sales.payments (id, transaction_id, method, amount, reference, paid_at)
SELECT
    gen_random_uuid(),
    r.transaction_id,
    rp.method,
    rp.amount,
    'RECEIVABLE_PAYMENT:' || rp.payment_number,
    rp.paid_at
FROM receivable.receivable_payments rp
JOIN receivable.receivables r ON r.id = rp.receivable_id
WHERE r.source = 'SALE'
  AND r.transaction_id IS NOT NULL
  AND rp.entry_type = 'PAYMENT'
  AND rp.id NOT IN (
      SELECT reversed_payment_id FROM receivable.receivable_payments WHERE reversed_payment_id IS NOT NULL
  )
  AND NOT EXISTS (
      SELECT 1 FROM sales.payments sp
      WHERE sp.transaction_id = r.transaction_id
        AND sp.method = rp.method
        AND sp.amount = rp.amount
  );
