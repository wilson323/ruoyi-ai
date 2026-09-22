/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `requirement_pools`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `requirement_pools` (
  `id` bigint NOT NULL,
  `project_id` bigint DEFAULT NULL,
  `title` varchar(256) COLLATE utf8mb4_general_ci NOT NULL,
  `description` text COLLATE utf8mb4_general_ci,
  `source` varchar(32) COLLATE utf8mb4_general_ci NOT NULL,
  `priority` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'MEDIUM',
  `status` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'SUBMITTED',
  `submitter_id` bigint NOT NULL,
  `reviewer_id` bigint DEFAULT NULL,
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id` varchar(20) COLLATE utf8mb4_general_ci DEFAULT '000000',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_req_project` (`project_id`),
  KEY `idx_req_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='需求池';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `receipt_ledgers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `receipt_ledgers` (
  `id` bigint NOT NULL,
  `project_id` bigint NOT NULL,
  `bonus_pool_id` bigint DEFAULT NULL,
  `receipt_month` varchar(7) COLLATE utf8mb4_general_ci NOT NULL,
  `receipt_amount` decimal(18,2) NOT NULL,
  `refund_amount` decimal(18,2) DEFAULT '0.00',
  `net_amount` decimal(18,2) GENERATED ALWAYS AS ((`receipt_amount` - `refund_amount`)) STORED,
  `voucher_url` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `voucher_hash` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `source` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'RECEIPT',
  `window_start` date DEFAULT NULL,
  `window_end` date DEFAULT NULL,
  `in_window` tinyint(1) GENERATED ALWAYS AS (((`receipt_month` >= date_format(`window_start`,_utf8mb4'%Y-%m')) and (`receipt_month` <= date_format(`window_end`,_utf8mb4'%Y-%m')))) STORED,
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id` varchar(20) COLLATE utf8mb4_general_ci DEFAULT '000000',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_receipt_project_month` (`project_id`,`receipt_month`,`tenant_id`,`del_flag`),
  KEY `idx_receipt_bonus_pool` (`bonus_pool_id`),
  KEY `idx_receipt_window` (`window_start`,`window_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售回款台账P3-4.1';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `switching_acceptances`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `switching_acceptances` (
  `id` bigint NOT NULL,
  `project_id` bigint DEFAULT NULL,
  `month` varchar(7) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `ran_at` datetime DEFAULT NULL,
  `ran_by` bigint DEFAULT NULL,
  `report_json` text COLLATE utf8mb4_general_ci,
  `diff_rate` decimal(18,4) DEFAULT NULL,
  `passed` tinyint(1) DEFAULT NULL,
  `is_locked` tinyint(1) DEFAULT NULL,
  `locked_at` datetime DEFAULT NULL,
  `locked_by` bigint DEFAULT NULL,
  `unlock_reason` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `unlocked_at` datetime DEFAULT NULL,
  `unlocked_by` bigint DEFAULT NULL,
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0',
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_sa_proj_month` (`project_id`,`month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='切换验收';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

