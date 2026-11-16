-- RouteMind (Spec 15a): delivery vehicles, delivery orders, tours with their stops.
create table delivery_vehicle (
  id              text primary key,
  name            text not null,
  max_kg          int  not null check (max_kg > 0),
  max_pallets     int  not null check (max_pallets > 0),
  status          text not null default 'AVAILABLE' check (status in
                    ('AVAILABLE', 'LOADING', 'EN_ROUTE', 'RETURNING', 'MAINTENANCE', 'BROKEN_DOWN')),
  tour_id         text,
  leg             int    not null default 0,
  leg_start_ms    bigint not null default 0,
  leg_end_ms      bigint not null default 0,
  breakdown_at_ms bigint,
  ready_at_ms     bigint not null default 0,
  version         int    not null default 0
);
insert into delivery_vehicle (id, name, max_kg, max_pallets) values
  ('LKW-1', 'LKW 7,5 t', 3000, 15),
  ('LKW-2', 'LKW 7,5 t', 3000, 15),
  ('LW-1', 'Lieferwagen', 1000, 4),
  ('LW-2', 'Lieferwagen', 1000, 4);

create table delivery_order (
  id                 text   primary key,
  order_id           text   not null unique,
  customer_id        text   not null,
  product_id         text   not null references product,
  quantity           int    not null check (quantity > 0),
  stored_quantity    int    not null default 0 check (stored_quantity >= 0),
  weight_kg          int    not null check (weight_kg >= 0),
  pallets            int    not null check (pallets >= 0),
  priority           text   not null check (priority in ('NORMAL', 'HOCH', 'EILIG')),
  earliest_ms        bigint not null,
  latest_ms          bigint not null,
  status             text   not null check (status in ('CREATED', 'AWAITING_GOODS', 'READY_TO_LOAD', 'LOADED',
                       'IN_TRANSIT', 'DELIVERED', 'FAILED', 'CANCELLED')),
  tour_id            text,
  planned_arrival_ms bigint,
  delivered_at_ms    bigint,
  reservation_id     text,
  transport_id       text,
  failures           int    not null default 0 check (failures >= 0),
  created_at_ms      bigint not null,
  version            int    not null default 0
);

create table tour (
  id            text   primary key,
  plan_id       text   not null,
  vehicle_id    text   not null references delivery_vehicle,
  depart_at_ms  bigint not null,
  status        text   not null check (status in ('PLANNED', 'LOADING', 'EN_ROUTE', 'COMPLETED', 'REPLANNED',
                  'INTERRUPTED')),
  km            double precision not null,
  cost          double precision not null,
  started_at_ms bigint,
  ended_at_ms   bigint,
  version       int    not null default 0
);

create table tour_stop (
  tour_id            text   not null references tour on delete cascade,
  seq                int    not null check (seq > 0),
  delivery_id        text   not null,
  customer_id        text   not null,
  planned_arrival_ms bigint not null,
  arrived_at_ms      bigint,
  primary key (tour_id, seq)
);
