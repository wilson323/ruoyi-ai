-- MySQL dump 10.13  Distrib 26.7.0, for macos26.6 (arm64)
--
-- Host: localhost    Database: ipd_dev
-- ------------------------------------------------------
-- Server version	8.0.46

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `deletion_requests`
--

DROP TABLE IF EXISTS `deletion_requests`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `deletion_requests` (
  `id` bigint NOT NULL COMMENT '主键（雪花）',
  `entity_type` varchar(32) NOT NULL COMMENT '目标实体类型',
  `entity_id` bigint NOT NULL COMMENT '目标实体ID',
  `entity_snapshot` json DEFAULT NULL COMMENT '删除前快照',
  `reason` varchar(500) NOT NULL COMMENT '删除理由',
  `requester_id` bigint NOT NULL,
  `status` varchar(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT|LEADER_REVIEW|ADMIN_REVIEW|DELETED|REJECTED',
  `leader_id` bigint DEFAULT NULL,
  `leader_decision` varchar(16) DEFAULT NULL COMMENT 'APPROVE|REJECT',
  `leader_decided_at` datetime DEFAULT NULL,
  `leader_due_at` datetime DEFAULT NULL COMMENT '组长审核期限（deletion.leaderDeadlineDays=2 工作日）',
  `admin_id` bigint DEFAULT NULL,
  `admin_decision` varchar(16) DEFAULT NULL,
  `admin_decided_at` datetime DEFAULT NULL,
  `admin_due_at` datetime DEFAULT NULL COMMENT '超管审核期限（deletion.adminDeadlineDays=2 工作日）',
  `executed_at` datetime DEFAULT NULL COMMENT '实际执行软删除时间',
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id` varchar(20) DEFAULT '000000',
  `del_flag` char(1) DEFAULT '0',
  `remark` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_dr_entity` (`entity_type`,`entity_id`),
  KEY `idx_del_status_leader` (`status`,`leader_due_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IPD 删除申请（两级审核，禁直接物理删除 G-02）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `deletion_requests`
--
-- WHERE:  id IN (2096373346072539138,2096381707870662657)

LOCK TABLES `deletion_requests` WRITE;
/*!40000 ALTER TABLE `deletion_requests` DISABLE KEYS */;
INSERT INTO `deletion_requests` VALUES (2096373346072539138,'products',999,'{\"name\": \"test\"}','P0-6.1 集成验收',900101,'REJECTED',900101,'APPROVE','2026-09-06 07:02:51','2026-09-08 07:02:09',900101,'REJECT','2026-09-06 07:02:51','2026-09-08 07:02:51',NULL,-1,-1,'2026-09-06 07:02:09',-1,'2026-09-25 21:41:53','000000','1','R215/R217 悬空外键清理 20260925: entity_id 从未真实存在(测试探针)。原remark:');
INSERT INTO `deletion_requests` VALUES (2096381707870662657,'products',1,'{\"k\": \"v\"}','P0-6.3 round9 HTTP',900101,'WITHDRAWN',NULL,NULL,NULL,'2026-09-09 07:35:23',NULL,NULL,NULL,NULL,NULL,-1,-1,'2026-09-06 07:35:23',-1,'2026-09-25 21:41:53','000000','1','R215/R217 悬空外键清理 20260925: entity_id 从未真实存在(测试探针)。原remark:');
/*!40000 ALTER TABLE `deletion_requests` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-25  7:49:03
