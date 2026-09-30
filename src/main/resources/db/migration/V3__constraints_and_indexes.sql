alter table purchase_order
    add constraint fk_purchase_order_customer foreign key (customer_id) references customer (id);
alter table order_line
    add constraint fk_order_line_order foreign key (order_id) references purchase_order (id);
alter table order_line
    add constraint fk_order_line_product foreign key (product_id) references product (id);

-- Supports "newest first" listing for both OFFSET and keyset pagination.
create index idx_purchase_order_created_at_id on purchase_order (created_at, id);
create index idx_purchase_order_customer on purchase_order (customer_id);
create index idx_order_line_order on order_line (order_id);
create index idx_order_line_product on order_line (product_id);

analyze customer;
analyze product;
analyze purchase_order;
analyze order_line;
