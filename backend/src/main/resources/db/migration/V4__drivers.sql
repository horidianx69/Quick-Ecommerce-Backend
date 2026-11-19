-- One row per delivery driver. The key is the user's id: a user is a driver or is not.
create table drivers (
    user_id    bigint primary key references users (id),
    phone      text not null,
    status     text not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'SUSPENDED')),
    online     boolean not null default false,
    -- Only the latest position is kept, overwritten by every ping. No history.
    latitude   double precision check (latitude between -90 and 90),
    longitude  double precision check (longitude between -180 and 180),
    located_at timestamptz,
    created_at timestamptz not null default now()
);

alter table orders
    add column driver_id     bigint references users (id),
    -- The customer reads this code to the driver at the door; the driver must
    -- enter it to mark the order delivered.
    add column delivery_code text,
    add column delivered_at  timestamptz;

update orders set delivery_code = lpad(floor(random() * 10000)::int::text, 4, '0');
alter table orders alter column delivery_code set not null;

-- A driver carries one order at a time. The database enforces it, so two
-- "accept" taps at the same moment cannot both win.
create unique index orders_one_active_per_driver on orders (driver_id)
    where driver_id is not null and status in ('ACCEPTED', 'READY', 'PICKED_UP');

-- The pool of jobs waiting for a driver. Kept tiny by the condition, so finding open jobs stays fast
-- however many finished orders pile up.
create index orders_open_jobs_idx on orders (id)
    where driver_id is null and status in ('ACCEPTED', 'READY');

-- A driver's past deliveries.
create index orders_driver_idx on orders (driver_id, id) where driver_id is not null;
