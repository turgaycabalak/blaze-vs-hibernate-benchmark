-- Deterministic seed: every value is derived from the row number with plain arithmetic,
-- no random(). The same placeholders always produce byte-identical data, so readers
-- running the benchmark get the same dataset.
--
-- Sizes come from Flyway placeholders (spring.flyway.placeholders.*):
--   customers, products, orders. Each order has 1..5 lines (avg 3).

insert into customer (id, name, email, country, created_at)
select i,
       'Customer ' || i,
       'customer' || i || '@example.com',
       (array ['TR','DE','US','GB','FR','NL','ES','IT'])[1 + (i % 8)],
       timestamp '2020-01-01' + i * interval '17 minutes'
from generate_series(1::bigint, ${customers}) as i;

insert into product (id, name, category, price)
select i,
       'Product ' || i,
       (array ['books','electronics','garden','toys','food','sports'])[1 + (i % 6)],
       5 + (i * 37 % 995)
from generate_series(1::bigint, ${products}) as i;

-- created_at grows with id plus up to one hour of jitter, so created_at order
-- differs from id order and some timestamps collide (the keyset needs id as tie-breaker).
insert into purchase_order (id, customer_id, status, created_at)
select i,
       1 + (i * 7919 % ${customers}),
       (array ['PENDING','PAID','SHIPPED','DELIVERED','CANCELLED'])[1 + (i * 31 % 5)],
       timestamp '2022-01-01' + i * interval '1 minute' + (i * 104729 % 3600) * interval '1 second'
from generate_series(1::bigint, ${orders}) as i;

insert into order_line (id, order_id, product_id, quantity, unit_price)
select row_number() over (order by o.id, n),
       o.id,
       p.product_id,
       1 + ((o.id + n) % 4),
       5 + (p.product_id * 37 % 995)
from purchase_order o
         cross join lateral generate_series(1, 1 + (o.id % 5)::int) as n
         cross join lateral (select 1 + ((o.id * 131 + n * 17) % ${products}) as product_id) p;

update purchase_order o
set total_amount = s.total
from (select order_id, sum(quantity * unit_price) as total
      from order_line
      group by order_id) s
where s.order_id = o.id;

-- Keep identity columns ahead of the seeded ids.
select setval(pg_get_serial_sequence('customer', 'id'), (select max(id) from customer));
select setval(pg_get_serial_sequence('product', 'id'), (select max(id) from product));
select setval(pg_get_serial_sequence('purchase_order', 'id'), (select max(id) from purchase_order));
select setval(pg_get_serial_sequence('order_line', 'id'), (select max(id) from order_line));
