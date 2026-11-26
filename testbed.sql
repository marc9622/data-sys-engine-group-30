CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_prodname STRING, o_quantity LONG);
CREATE TABLE products (p_name STRING, p_price DOUBLE);
CREATE TABLE customers (c_name STRING, c_city STRING);
CREATE TABLE cities (ct_city STRING, ct_country STRING, ct_population LONG);

COPY orders FROM 'src/test/resources/orders.csv';
COPY products FROM 'src/test/resources/products.csv';
COPY customers FROM 'src/test/resources/customers.csv';
COPY cities FROM 'src/test/resources/cities.csv';

SELECT * FROM orders, customers
WHERE o_custname = c_name;

SELECT * FROM orders, customers, products
WHERE o_custname = c_name AND o_prodname = p_name;

SELECT * FROM orders, customers, products, cities
WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;

SELECT * FROM orders, customers, products
WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';
