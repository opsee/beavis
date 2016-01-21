--name: insert-into-assertions<!
-- Inserts a new record into the assertions table.
insert into bartnet_assertions (check_id, customer_id, relationship, "key", "value", operand) values
        (:check_id, :customer_id::uuid, :relationship::relationship_type, :key, :value, :operand);

--name: get-assertions-by-check-and-customer
-- Retrieves assertions by the check_id and customer_id.
select * from bartnet_assertions where check_id=:check_id and customer_id=:customer_id::uuid;

--name: get-assertions-by-customer
-- Retrieves all of the assertions for a customer_id.
select * from bartnet_assertions where customer_id=:customer_id::uuid;

--name: get-assertions-by-check
select * from bartnet_assertions where check_id=:check_id;

--name: delete-assertions-by-check-and-customer!
-- Deletes an assertion.
delete from bartnet_assertions where check_id=:check_id and customer_id=:customer_id::uuid;

--name: get-assertions
select * from bartnet_assertions;

----------------------------------------------

--name: insert-into-notifications<!
insert into notifications (check_id, customer_id, type, "value") values
        (:check_id, :customer_id::uuid, :type, :value);

--name: get-notifications-by-check-and-customer
select * from notifications where customer_id=:customer_id::uuid and check_id=:check_id;

--name: get-notifications-by-customer
select * from notifications where customer_id=:customer_id::uuid;

--name: get-notifications-by-check
select * from notifications where check_id=:check_id;

--name: delete-notifications-by-check-and-customer!
delete from notifications where customer_id=:customer_id::uuid and check_id=:check_id;

--name: get-notifications
select * from notifications;

----------------------------------------------

--name: create-alert!
insert into alerts (customer_id, check_id, check_name, state) values (:customer_id::uuid, :check_id, :check_name, 'open');

--name: resolve-alert!
update alerts set state='resolved', updated_at=current_timestamp where id=:alert_id;

--name: get-latest-alert
select * from alerts where customer_id=:customer_id::uuid and check_id=:check_id order by created_at desc limit 1;

--name: get-alerts-by-check
select * from alerts where check_id=:check_id;

--name: delete-alerts-by-check-and-customer!
delete from alerts where customer_id=:customer_id::uuid and check_id=:check_id;
