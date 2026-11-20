CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_prodname STRING, o_quantity LONG);
CREATE TABLE products (p_name STRING, p_price DOUBLE);
CREATE TABLE customers (c_name STRING, c_city STRING);
CREATE TABLE cities (ct_city STRING, ct_country STRING, ct_population LONG);

COPY orders FROM 'testbedData/orders.csv';
COPY products FROM 'testbedData/products.csv';
COPY customers FROM 'testbedData/customers.csv';
COPY cities FROM 'testbedData/cities.csv';

SELECT * FROM orders, customers
WHERE o_custname = c_name;

SELECT * FROM orders, customers, products
WHERE o_custname = c_name AND o_prodname = p_name;

SELECT * FROM orders, customers, products, cities
WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;

SELECT * FROM orders, customers, products
WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';
