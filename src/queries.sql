--name: insert-into-assertions!
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

--name: delete-assertion-by-check-and-customer!
-- Deletes an assertion.
delete from assertions where check_id=:check_id and customer_id=:customer_id::uuid;