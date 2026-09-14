-- Step 3 of 3 for example/iceberg/default/eqren — see docs/fixtures/eqren.sql.
--
-- The rename leaves the equality delete file holding a column called `name`; the table now
-- calls field 2 `label`. The insert after it writes a row whose `label` is one of the deleted
-- values, at a sequence number above the delete's, so the delete does not reach it.
--
-- Oracle, Spark 3.5.5 / Iceberg 1.8.1 (the SELECT at the end prints it):
--   1 alpha 12.34 / 4 delta 99.99 / 5 echo 1.00 / 7 golf 3.75 / 8 bravo 4.00

ALTER TABLE lens.default.eqren RENAME COLUMN name TO label;

INSERT INTO lens.default.eqren VALUES (8, 'bravo', CAST(4.00 AS DECIMAL(9,2)));

SELECT id, label, amount FROM lens.default.eqren ORDER BY id;
