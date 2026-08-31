-- Batch 3A: persist the optional one-time manager approval used by a cashier Void.
-- OWNER/ADMIN direct voids deliberately keep this column NULL.

ALTER TABLE sales.transaction_voids
    ADD COLUMN manager_approval_id UUID
        REFERENCES system.manager_approvals(id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_transaction_void_manager_approval
    ON sales.transaction_voids(manager_approval_id)
    WHERE manager_approval_id IS NOT NULL;
