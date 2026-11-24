create table stripe_recipients (
    kind text not null check (kind in ('SHOP', 'DRIVER')),
    recipient_id bigint not null,
    live boolean not null,
    account_id text not null,
    primary key (kind, recipient_id, live),
    unique (account_id, live)
);

create table stripe_transfers (
    id uuid primary key,
    live boolean not null,
    kind text not null,
    recipient_id bigint not null,
    order_id bigint references orders (id),
    period_start date,
    account_id text not null,
    amount_cents integer not null check (amount_cents > 0),
    source_charge text,
    state text not null check (state in ('QUEUED', 'TRANSFERRED', 'REVIEW_REQUIRED')),
    transfer_id text unique,
    first_attempt_at timestamptz,
    attempts integer not null default 0,
    due_at timestamptz,
    lease_until timestamptz,
    lease_token uuid,
    foreign key (kind, recipient_id, live) references stripe_recipients (kind, recipient_id, live),
    unique nulls not distinct (kind, recipient_id, live, order_id, period_start),
    check ((kind = 'SHOP' and order_id is not null and source_charge is not null and period_start is null)
        or (kind = 'DRIVER' and order_id is null and source_charge is null and period_start is not null))
);
create index stripe_transfers_due_idx on stripe_transfers (due_at) where due_at is not null;
