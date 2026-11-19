-- One row per order to one shop. A multi-shop checkout (later phase) will be
-- several of these rows grouped under a parent row, so nothing here changes.
create table orders (
    id                 bigint generated always as identity primary key,
    customer_id        bigint not null references users (id),
    shop_id            bigint not null references shops (id),
    -- Sent by the client. A retry or double tap with the same key returns the first order.
    idempotency_key    text not null,
    status             text not null check (status in
        ('PLACED', 'ACCEPTED', 'READY', 'PICKED_UP', 'DELIVERED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    -- Delivery address, copied at order time so later edits do not change past orders.
    address_line       text not null,
    city               text not null,
    province           text not null,
    postal_code        text not null,
    latitude           double precision not null check (latitude between -90 and 90),
    longitude          double precision not null check (longitude between -180 and 180),
    note               text,
    delivery_fee_cents integer not null check (delivery_fee_cents >= 0),
    created_at         timestamptz not null,
    unique (customer_id, idempotency_key)
);
create index orders_shop_status_idx on orders (shop_id, status, id);
create index orders_customer_idx on orders (customer_id, id);
-- Small index for the job that expires orders a shop never answered.
create index orders_placed_idx on orders (created_at) where status = 'PLACED';

create table order_items (
    id                   bigint generated always as identity primary key,
    order_id             bigint not null references orders (id) on delete cascade,
    -- Becomes null if the shop later removes the product; the order keeps its own copy below.
    shop_product_id      bigint references shop_products (id) on delete set null,
    product_id           bigint not null references catalog_products (id),
    name                 text not null,
    quantity             integer not null check (quantity > 0),
    -- Both prices are frozen at order time: what the shop earns, and what the customer pays.
    shop_price_cents     integer not null check (shop_price_cents > 0),
    customer_price_cents integer not null check (customer_price_cents > 0),
    unavailable          boolean not null default false
);
create index order_items_order_idx on order_items (order_id);
