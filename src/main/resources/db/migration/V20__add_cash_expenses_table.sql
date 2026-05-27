CREATE TABLE sales.cash_expenses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES sales.cash_sessions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES system.users(id),
    amount NUMERIC(15, 2) NOT NULL,
    description TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_cash_expenses_session_id ON sales.cash_expenses(session_id);
