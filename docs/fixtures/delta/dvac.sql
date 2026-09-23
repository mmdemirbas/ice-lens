-- dvac — OPTIMIZE and VACUUM, each with a copy of the table taken before it ran.
--   dopt   five single-row inserts and a DELETE that rewrote one file, before OPTIMIZE
--   dvac   the same table after OPTIMIZE compacted the four small files into one, before VACUUM
--   dvaca  dvac after VACUUM RETAIN 0 HOURS deleted the files no live version needs
-- A DRY RUN before the VACUUM prints the files it will delete.
CREATE TABLE dvac (id INT, v STRING) USING delta;
INSERT INTO dvac VALUES (1, 'a');
INSERT INTO dvac VALUES (2, 'b');
INSERT INTO dvac VALUES (3, 'c');
INSERT INTO dvac VALUES (4, 'd');
INSERT INTO dvac VALUES (5, 'e');
DELETE FROM dvac WHERE id = 5;
--! cp -R /wh/dvac /wh/dopt
OPTIMIZE dvac;
--! cp -R /wh/dvac /wh/dvaca
VACUUM '/wh/dvaca' RETAIN 0 HOURS DRY RUN;
VACUUM '/wh/dvaca' RETAIN 0 HOURS;
SELECT 'rows', * FROM dvac ORDER BY id;
DESCRIBE HISTORY dvac;
DESCRIBE HISTORY '/wh/dvaca';
