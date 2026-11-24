alter table orders
    add column delivery_code_attempts integer not null default 0,
    add column delivery_code_window_expires_at timestamptz,
    add constraint orders_delivery_code_window_check check (
        (delivery_code_attempts = 0 and delivery_code_window_expires_at is null)
        or (delivery_code_attempts > 0 and delivery_code_window_expires_at is not null)
    );
