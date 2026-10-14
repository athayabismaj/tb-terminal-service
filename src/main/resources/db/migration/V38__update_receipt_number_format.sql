-- Update default format for receipt_number to TRX-DDMMYYYY-001
ALTER TABLE sales.transactions
    ALTER COLUMN receipt_number SET DEFAULT (
        'TRX-' || TO_CHAR(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Jakarta', 'DDMMYYYY') || '-' ||
        LPAD(NEXTVAL('sales.receipt_number_seq')::text, 3, '0')
    );

-- Update receivable payment number format to RCP-DDMMYYYY-001
CREATE OR REPLACE FUNCTION receivable.next_receivable_payment_number()
RETURNS VARCHAR AS $$
BEGIN
    RETURN 'RCP-' || TO_CHAR(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Jakarta', 'DDMMYYYY') ||
        '-' || LPAD(NEXTVAL('receivable.receivable_payment_number_seq')::TEXT, 3, '0');
END;
$$ LANGUAGE plpgsql VOLATILE;

-- Update refund number format to RFD-DDMMYYYY-001
CREATE OR REPLACE FUNCTION sales.next_refund_number()
RETURNS VARCHAR AS $$
BEGIN
    RETURN 'RFD-' || TO_CHAR(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Jakarta', 'DDMMYYYY') ||
        '-' || LPAD(NEXTVAL('sales.refund_number_seq')::TEXT, 3, '0');
END;
$$ LANGUAGE plpgsql VOLATILE;
