--name: insert-into-assertions<!
-- Inserts a new record into the assertions table.
insert into assertions (check_id, customer_id, relationship, "key", "value", operand) values
        (:check_id, :customer_id::uuid, :relationship::relationship_type, :key, :value, :operand);

--name: get-assertions-by-check-and-customer
-- Retrieves assertions by the check_id and customer_id.
select * from assertions where check_id=:check_id and customer_id=:customer_id::uuid;

--name: get-assertions-by-customer
-- Retrieves all of the assertions for a customer_id.
select * from assertions where customer_id=:customer_id::uuid;

--name: get-assertions-by-check
select * from assertions where check_id=:check_id;

--name: delete-assertions-by-check-and-customer!
-- Deletes an assertion.
delete from assertions where check_id=:check_id and customer_id=:customer_id::uuid;

--name: get-assertions
select * from assertions;

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