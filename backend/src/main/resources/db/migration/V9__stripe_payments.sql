alter table orders drop constraint orders_status_check;
alter table orders add constraint orders_status_check check (status in
    ('PAYMENT_PENDING', 'CAPTURE_PENDING', 'PLACED', 'ACCEPTED', 'READY', 'PICKED_UP',
     'DELIVERED', 'REJECTED', 'CANCELLED', 'EXPIRED'));

create table order_payments (
    order_id bigint not null references orders (id),
    reference uuid primary key,
    kind text not null check (kind in ('ORDER', 'TIP')),
    state text not null check (state in
        ('OPENING','OPEN','AUTHORIZED','CAPTURING','CAPTURED','VOIDED','REFUNDING','REFUNDED','REVIEW_REQUIRED')),
    live boolean not null,
    authorized_cents bigint not null check (authorized_cents between 50 and 99999999),
    capture_cents bigint check (capture_cents between 50 and authorized_cents),
    expires_at timestamptz not null,
    return_url text not null,
    session_id text unique,
    checkout_url text,
    intent_id text unique,
    charge_id text unique,
    refund_id text unique,
    closing_at timestamptz,
    captured_at timestamptz,
    capture_until timestamptz,
    due_at timestamptz,
    lease_until timestamptz,
    lease_token uuid,
    failures integer not null default 0,
    webhook_pending boolean not null default false,
    unique (order_id, kind)
);
create index order_payments_due_idx on order_payments (due_at) where due_at is not null;
create index order_payments_expiry_idx on order_payments (expires_at) where kind = 'ORDER' and state in ('OPENING', 'OPEN');
create index order_tips_captured_idx on order_payments (captured_at) where kind = 'TIP' and state = 'CAPTURED';
