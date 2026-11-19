create table users (
    id            bigint generated always as identity primary key,
    email         text not null unique,
    password_hash text not null,
    name          text not null,
    role          text not null check (role in ('SHOP', 'ADMIN', 'CUSTOMER', 'DRIVER')),
    created_at    timestamptz not null default now()
);

create table shops (
    id           bigint generated always as identity primary key,
    -- ponytail: one shop per owner; drop the unique when an owner needs a second shop
    owner_id     bigint not null unique references users (id),
    name         text not null,
    phone        text not null,
    address_line text not null,
    city         text not null,
    province     text not null,
    postal_code  text not null,
    -- ponytail: plain lat/lng; add a PostGIS geography column + GiST index
    -- in the migration that brings the "shops within 10 km" query
    latitude     double precision not null check (latitude between -90 and 90),
    longitude    double precision not null check (longitude between -180 and 180),
    status       text not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'SUSPENDED')),
    created_at   timestamptz not null default now()
);
create index shops_status_idx on shops (status);

create table shop_hours (
    shop_id     bigint not null references shops (id) on delete cascade,
    day_of_week text not null check (day_of_week in
        ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')),
    opens       time not null,
    closes      time not null,
    primary key (shop_id, day_of_week),
    check (closes > opens)
);

create table catalog_products (
    id                   bigint generated always as identity primary key,
    name                 text not null,
    brand                text,
    barcode              text unique,
    category             text not null,
    tax_category         text not null check (tax_category in ('ZERO_RATED', 'TAXABLE')),
    image_url            text,
    status               text not null check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by_shop_id bigint references shops (id),
    created_at           timestamptz not null default now()
);
create index catalog_products_status_idx on catalog_products (status);

create table shop_products (
    id          bigint generated always as identity primary key,
    shop_id     bigint not null references shops (id) on delete cascade,
    product_id  bigint not null references catalog_products (id),
    price_cents integer not null check (price_cents > 0),
    quantity    integer not null default 0 check (quantity >= 0),
    unique (shop_id, product_id)
);
