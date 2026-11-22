-- ---------------------------------------------------------------- prices
-- Prices and fees admin can change without a redeploy. Always exactly one row.
create table platform_settings (
    id                     integer primary key default 1 check (id = 1),
    -- Customer price = shop price x markup / 100. 150 = 1.5x. Never below the shop's own price.
    default_markup_percent integer not null check (default_markup_percent between 100 and 1000),
    -- What the customer pays per order for delivery.
    delivery_fee_cents     integer not null check (delivery_fee_cents between 0 and 10000),
    -- What the driver earns per delivery.
    driver_pay_cents       integer not null check (driver_pay_cents between 0 and 10000),
    updated_at             timestamptz not null default now()
);
-- The values the app used from config until now.
insert into platform_settings (default_markup_percent, delivery_fee_cents, driver_pay_cents) values (150, 499, 499);

-- A different markup for one catalog category. Categories with no row use the default.
create table category_markups (
    category       text primary key,
    markup_percent integer not null check (markup_percent between 100 and 1000)
);

-- --------------------------------------------------------------- orders
-- Driver pay is frozen on the order, like the prices, so a later settings
-- change never changes what a past delivery paid.
alter table orders
    add column driver_pay_cents integer,
    add column cancel_reason    text;
update orders set driver_pay_cents = delivery_fee_cents;
alter table orders alter column driver_pay_cents set not null;

-- The admin order board filters all orders by status.
create index orders_status_idx on orders (status, id);

-- ---------------------------------------------------------------- shops
-- Opening hours are local times, so each shop needs its time zone.
alter table shops add column time_zone text;
update shops set time_zone = case province
    when 'BC' then 'America/Vancouver'
    when 'AB' then 'America/Edmonton'
    when 'SK' then 'America/Regina'
    when 'MB' then 'America/Winnipeg'
    when 'ON' then 'America/Toronto'
    when 'QC' then 'America/Toronto'
    when 'NB' then 'America/Halifax'
    when 'NS' then 'America/Halifax'
    when 'PE' then 'America/Halifax'
    when 'NL' then 'America/St_Johns'
    when 'YT' then 'America/Whitehorse'
    when 'NT' then 'America/Yellowknife'
    when 'NU' then 'America/Iqaluit'
end;
alter table shops alter column time_zone set not null;

-- ---------------------------------------------------------------- users
-- A suspended account cannot log in, and tokens it already holds stop working.
alter table users add column disabled boolean not null default false;
