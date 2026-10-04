-- Existing retirement record only. No business approval or product status changes.
-- Approval authority: current product-line leader (user decision 2026-10-04), not historical two-level route.
-- Dates are approved policy records; external ordering/production integrations do not exist in this repository.
SET @retirement_column_exists=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='product_retirements' AND COLUMN_NAME='marketing_stop_at');
SET @retirement_ddl=IF(@retirement_column_exists=0,'ALTER TABLE product_retirements ADD COLUMN marketing_stop_at DATETIME DEFAULT NULL','SELECT 1');
PREPARE retirement_stmt FROM @retirement_ddl;
EXECUTE retirement_stmt;
DEALLOCATE PREPARE retirement_stmt;
SET @retirement_column_exists=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='product_retirements' AND COLUMN_NAME='order_stop_at');
SET @retirement_ddl=IF(@retirement_column_exists=0,'ALTER TABLE product_retirements ADD COLUMN order_stop_at DATETIME DEFAULT NULL','SELECT 1');
PREPARE retirement_stmt FROM @retirement_ddl;
EXECUTE retirement_stmt;
DEALLOCATE PREPARE retirement_stmt;
SET @retirement_column_exists=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='product_retirements' AND COLUMN_NAME='production_stop_at');
SET @retirement_ddl=IF(@retirement_column_exists=0,'ALTER TABLE product_retirements ADD COLUMN production_stop_at DATETIME DEFAULT NULL','SELECT 1');
PREPARE retirement_stmt FROM @retirement_ddl;
EXECUTE retirement_stmt;
DEALLOCATE PREPARE retirement_stmt;
SET @retirement_column_exists=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='product_retirements' AND COLUMN_NAME='spare_support_stop_at');
SET @retirement_ddl=IF(@retirement_column_exists=0,'ALTER TABLE product_retirements ADD COLUMN spare_support_stop_at DATETIME DEFAULT NULL','SELECT 1');
PREPARE retirement_stmt FROM @retirement_ddl;
EXECUTE retirement_stmt;
DEALLOCATE PREPARE retirement_stmt;
SET @retirement_column_exists=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='product_retirements' AND COLUMN_NAME='software_support_stop_at');
SET @retirement_ddl=IF(@retirement_column_exists=0,'ALTER TABLE product_retirements ADD COLUMN software_support_stop_at DATETIME DEFAULT NULL','SELECT 1');
PREPARE retirement_stmt FROM @retirement_ddl;
EXECUTE retirement_stmt;
DEALLOCATE PREPARE retirement_stmt;
SET @retirement_column_exists=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='product_retirements' AND COLUMN_NAME='software_support_policy');
SET @retirement_ddl=IF(@retirement_column_exists=0,'ALTER TABLE product_retirements ADD COLUMN software_support_policy VARCHAR(2000) DEFAULT NULL','SELECT 1');
PREPARE retirement_stmt FROM @retirement_ddl;
EXECUTE retirement_stmt;
DEALLOCATE PREPARE retirement_stmt;
