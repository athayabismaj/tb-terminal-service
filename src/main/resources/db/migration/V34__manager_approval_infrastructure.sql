-- Batch 2: reusable, short-lived, one-time Manager Approval grants.
-- Credentials are deliberately never persisted in this table.

CREATE TABLE system.manager_approvals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requested_by_user_id UUID NOT NULL REFERENCES system.users(id) ON DELETE RESTRICT,
    approved_by_user_id UUID NOT NULL REFERENCES system.users(id) ON DELETE RESTRICT,
    action VARCHAR(40) NOT NULL CHECK (action IN (
        'VOID_TRANSACTION',
        'REFUND_TRANSACTION',
        'DISCOUNT_OVERRIDE',
        'RECEIVABLE_REVERSAL'
    )),
    resource_type VARCHAR(40),
    resource_id UUID,
    status VARCHAR(16) NOT NULL DEFAULT 'APPROVED'
        CHECK (status IN ('APPROVED', 'USED', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    CONSTRAINT ck_manager_approval_no_self_approval
        CHECK (requested_by_user_id <> approved_by_user_id),
    CONSTRAINT ck_manager_approval_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT ck_manager_approval_used_at CHECK (
        (status = 'USED' AND used_at IS NOT NULL)
        OR (status IN ('APPROVED', 'EXPIRED') AND used_at IS NULL)
    ),
    CONSTRAINT ck_manager_approval_resource_pair CHECK (
        (resource_type IS NULL AND resource_id IS NULL)
        OR (resource_type IS NOT NULL AND resource_id IS NOT NULL)
    ),
    CONSTRAINT ck_manager_approval_action_scope CHECK (
        (action IN ('VOID_TRANSACTION', 'REFUND_TRANSACTION', 'DISCOUNT_OVERRIDE')
            AND resource_type = 'TRANSACTION' AND resource_id IS NOT NULL)
        OR
        (action = 'RECEIVABLE_REVERSAL'
            AND resource_type = 'RECEIVABLE_PAYMENT' AND resource_id IS NOT NULL)
    )
);

CREATE INDEX idx_manager_approvals_active_expiry
    ON system.manager_approvals(expires_at)
    WHERE status = 'APPROVED';

CREATE INDEX idx_manager_approvals_requester_created
    ON system.manager_approvals(requested_by_user_id, created_at DESC);

CREATE INDEX idx_manager_approvals_approver_created
    ON system.manager_approvals(approved_by_user_id, created_at DESC);
