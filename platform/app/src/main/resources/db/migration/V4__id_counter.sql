-- Counters for readable business ids (IdSource). Reset clears them together with the business tables.
create table id_counter (
  prefix text primary key,
  value  bigint not null check (value > 0)
);
