-- R217 方案C 幽灵项目9140004遗产组 标注前备份 2026-09-25 07:49:37 由 mysqldump 生成
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
-- Table structure for table `bonus_pools`
--

DROP TABLE IF EXISTS `bonus_pools`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bonus_pools` (
  `id` bigint NOT NULL COMMENT '主键（雪花）',
  `project_id` bigint NOT NULL COMMENT '项目（1:1）',
  `target_sales` decimal(18,2) NOT NULL COMMENT '目标销售额（回款口径 salesSource=RECEIPT）',
  `pool_rate` decimal(5,4) NOT NULL DEFAULT '0.0500' COMMENT '奖金池比例（bonus.poolRate）',
  `base_pool` decimal(18,2) DEFAULT NULL COMMENT '基础奖金池=目标销售额×5%',
  `coefficient` decimal(5,2) DEFAULT NULL COMMENT '项目系数（S/A/B，G1 双签决定）',
  `achievement_rate` decimal(5,2) DEFAULT NULL COMMENT '回款达成率%',
  `tier_coefficient` decimal(5,2) DEFAULT NULL COMMENT '达成率 6 档阶梯系数',
  `final_pool` decimal(18,2) DEFAULT NULL COMMENT '最终奖金池',
  `distributions` json DEFAULT NULL COMMENT '个人分配结果（五维贡献+绩效系数）',
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT|CONFIRMED|DISTRIBUTED',
  `calculated_at` datetime DEFAULT NULL COMMENT '计算时间',
  `distributed_at` datetime DEFAULT NULL COMMENT '发放时间',
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id` varchar(20) DEFAULT '000000',
  `del_flag` char(1) DEFAULT '0',
  `remark` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bp_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IPD 项目奖金池（目标销售额×5%×系数→6档阶梯→分配）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `bonus_pools`
--
-- WHERE:  id=2097169984949182465

LOCK TABLES `bonus_pools` WRITE;
/*!40000 ALTER TABLE `bonus_pools` DISABLE KEYS */;
INSERT INTO `bonus_pools` VALUES (2097169984949182465,9140004,1200000.00,0.0500,60000.00,0.70,120.00,1.00,42000.00,'{\"rdShare\": 0.4, \"rdAmount\": 16800.0, \"marketShare\": 0.6, \"marketAmount\": 25200.0}','DISTRIBUTED','2026-09-08 11:47:43','2026-09-24 19:25:17',-1,-1,'2026-09-08 11:47:43',-1,'2026-09-24 19:25:17','000000','0',NULL);
/*!40000 ALTER TABLE `bonus_pools` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-25  7:49:37
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
-- Table structure for table `bonus_allocations`
--

DROP TABLE IF EXISTS `bonus_allocations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `bonus_allocations` (
  `id` bigint NOT NULL,
  `bonus_pool_id` bigint NOT NULL,
  `person_id` bigint NOT NULL,
  `role_in_project` varchar(16) NOT NULL,
  `contribution_rate` decimal(5,4) NOT NULL,
  `performance_coefficient` decimal(5,2) NOT NULL,
  `allocated_amount` decimal(18,2) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT',
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id` varchar(20) DEFAULT '000000',
  `del_flag` char(1) DEFAULT '0',
  `remark` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alloc_pool_person` (`bonus_pool_id`,`person_id`,`tenant_id`,`del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='奖金分配台账';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `bonus_allocations`
--
-- WHERE:  bonus_pool_id=2097169984949182465

LOCK TABLES `bonus_allocations` WRITE;
/*!40000 ALTER TABLE `bonus_allocations` DISABLE KEYS */;
INSERT INTO `bonus_allocations` VALUES (2103083343027867649,2097169984949182465,9110003,'MARKET_PM',0.4800,0.70,25200.00,'DRAFT',-1,-1,'2026-09-24 19:25:17',-1,'2026-09-24 19:25:17','000000','0',NULL);
INSERT INTO `bonus_allocations` VALUES (2103083343057227777,2097169984949182465,9110009,'RD_PM',0.3200,0.70,16800.00,'DRAFT',-1,-1,'2026-09-24 19:25:17',-1,'2026-09-24 19:25:17','000000','0',NULL);
/*!40000 ALTER TABLE `bonus_allocations` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-25  7:49:37
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
-- Table structure for table `kpi_records`
--

DROP TABLE IF EXISTS `kpi_records`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `kpi_records` (
  `id` bigint NOT NULL COMMENT '主键（雪花）',
  `project_id` bigint DEFAULT NULL,
  `person_id` bigint NOT NULL,
  `kpi_type` varchar(16) NOT NULL COMMENT 'FUNCTIONAL|SHARED（共担四项归集由产品组长）',
  `period` varchar(7) NOT NULL COMMENT '考核周期 YYYY-MM（每月 5 日截止 kpi.monthlyDeadlineDay）',
  `functional_score` decimal(5,2) DEFAULT NULL,
  `shared_detail` json DEFAULT NULL COMMENT '共担四项明细',
  `comprehensive_score` decimal(5,2) DEFAULT NULL COMMENT '综合得分（kpi.functionalWeight=0.6/0.4）',
  `segment` varchar(16) DEFAULT NULL COMMENT '在研分段',
  `revision` int DEFAULT NULL COMMENT '版本',
  `scored_by` bigint DEFAULT NULL,
  `scored_at` datetime DEFAULT NULL COMMENT '评分时间',
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT',
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id` varchar(20) DEFAULT '000000',
  `del_flag` char(1) DEFAULT '0',
  `remark` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_kpi_person_period` (`person_id`,`period`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IPD KPI 记录（功能+共担）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `kpi_records`
--
-- WHERE:  project_id=9140004

LOCK TABLES `kpi_records` WRITE;
/*!40000 ALTER TABLE `kpi_records` DISABLE KEYS */;
INSERT INTO `kpi_records` VALUES (2097220521606258690,9140004,9110003,'SHARED','2026-08',NULL,'{\"period\": \"2026-08\", \"source\": \"GROUP_LEADER_COLLECTION\", \"metrics\": [{\"code\": \"K01\", \"score\": 96.0, \"actual\": 800000, \"source\": \"FINANCE_OR_ERP\", \"target\": 1000000.0, \"weight\": 0.15, \"message\": \"销量/出货量达成率\", \"included\": true}, {\"code\": \"K02\", \"score\": 96.0, \"actual\": 12, \"source\": \"CHANNEL_CRM\", \"target\": 15, \"weight\": 0.1, \"message\": \"渠道商覆盖达成率\", \"included\": true}, {\"code\": \"K03\", \"score\": 98.0, \"actual\": 32, \"source\": \"NPS_SURVEY\", \"target\": 40, \"weight\": 0.1, \"message\": \"NPS=32\", \"included\": true}, {\"code\": \"K04\", \"score\": 92.0, \"actual\": 6, \"source\": \"SALES_ACCEPTANCE\", \"target\": 10, \"weight\": 0.05, \"message\": \"场景覆盖率\", \"included\": true}], \"revision\": 1, \"projectId\": 9140004, \"collectedBy\": 900101, \"participantIds\": [9110003, 9110009]}',96.00,'FULL_SHARED',1,900101,'2026-09-08 15:08:31','FINALIZED',NULL,900101,'2026-09-08 15:08:31',900101,'2026-09-08 15:08:31','000000','0',NULL);
INSERT INTO `kpi_records` VALUES (2097220521639813121,9140004,9110009,'SHARED','2026-08',NULL,'{\"period\": \"2026-08\", \"source\": \"GROUP_LEADER_COLLECTION\", \"metrics\": [{\"code\": \"K01\", \"score\": 96.0, \"actual\": 800000, \"source\": \"FINANCE_OR_ERP\", \"target\": 1000000.0, \"weight\": 0.15, \"message\": \"销量/出货量达成率\", \"included\": true}, {\"code\": \"K02\", \"score\": 96.0, \"actual\": 12, \"source\": \"CHANNEL_CRM\", \"target\": 15, \"weight\": 0.1, \"message\": \"渠道商覆盖达成率\", \"included\": true}, {\"code\": \"K03\", \"score\": 98.0, \"actual\": 32, \"source\": \"NPS_SURVEY\", \"target\": 40, \"weight\": 0.1, \"message\": \"NPS=32\", \"included\": true}, {\"code\": \"K04\", \"score\": 92.0, \"actual\": 6, \"source\": \"SALES_ACCEPTANCE\", \"target\": 10, \"weight\": 0.05, \"message\": \"场景覆盖率\", \"included\": true}], \"revision\": 1, \"projectId\": 9140004, \"collectedBy\": 900101, \"participantIds\": [9110003, 9110009]}',96.00,'FULL_SHARED',1,900101,'2026-09-08 15:08:31','FINALIZED',NULL,900101,'2026-09-08 15:08:31',900101,'2026-09-08 15:08:31','000000','0',NULL);
/*!40000 ALTER TABLE `kpi_records` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-25  7:49:37
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
-- Table structure for table `kpi_shared_confirms`
--

DROP TABLE IF EXISTS `kpi_shared_confirms`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `kpi_shared_confirms` (
  `id` bigint NOT NULL,
  `project_id` bigint DEFAULT NULL,
  `period` varchar(7) DEFAULT NULL,
  `person_id` bigint DEFAULT NULL,
  `metric_code` varchar(64) DEFAULT NULL,
  `metric_name` varchar(200) DEFAULT NULL,
  `weight` decimal(5,2) DEFAULT NULL,
  `deadline_at` datetime DEFAULT NULL,
  `status` varchar(16) DEFAULT NULL,
  `first_confirmed_by` bigint DEFAULT NULL,
  `first_confirmed_at` datetime DEFAULT NULL,
  `second_confirmed_by` bigint DEFAULT NULL,
  `second_confirmed_at` datetime DEFAULT NULL,
  `del_flag` char(1) DEFAULT '0',
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ksc_proj_period` (`project_id`,`period`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KPI 共担确认';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `kpi_shared_confirms`
--
-- WHERE:  project_id=9140004

LOCK TABLES `kpi_shared_confirms` WRITE;
/*!40000 ALTER TABLE `kpi_shared_confirms` DISABLE KEYS */;
INSERT INTO `kpi_shared_confirms` VALUES (2097220521736282113,9140004,'2026-08',900101,'K01','销量/出货量达成率',0.15,'2026-09-08 18:00:00','CONFIRMED',900101,'2026-09-08 15:26:51',900102,'2026-09-08 15:26:51','0',-1,-1,'2026-09-08 15:08:32',-1,'2026-09-08 15:26:51');
INSERT INTO `kpi_shared_confirms` VALUES (2097220521765642241,9140004,'2026-08',900101,'K02','渠道商覆盖达成率',0.10,'2026-09-08 18:00:00','CONFIRMED',900101,'2026-09-08 15:26:51',900102,'2026-09-08 15:26:51','0',-1,-1,'2026-09-08 15:08:32',-1,'2026-09-08 15:26:51');
INSERT INTO `kpi_shared_confirms` VALUES (2097220521803390978,9140004,'2026-08',900101,'K03','NPS 达成率',0.10,'2026-09-08 18:00:00','CONFIRMED',900101,'2026-09-08 15:26:51',900102,'2026-09-08 15:26:51','0',-1,-1,'2026-09-08 15:08:32',-1,'2026-09-08 15:26:51');
INSERT INTO `kpi_shared_confirms` VALUES (2097220521832751105,9140004,'2026-08',900101,'K04','场景覆盖率',0.05,'2026-09-08 18:00:00','CONFIRMED',900101,'2026-09-08 15:26:51',900102,'2026-09-08 15:26:51','0',-1,-1,'2026-09-08 15:08:32',-1,'2026-09-08 15:26:51');
/*!40000 ALTER TABLE `kpi_shared_confirms` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-25  7:49:37
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
-- Table structure for table `project_members`
--

DROP TABLE IF EXISTS `project_members`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `project_members` (
  `id` bigint NOT NULL COMMENT '主键（雪花）',
  `project_id` bigint NOT NULL COMMENT '项目',
  `person_id` bigint NOT NULL COMMENT '人员',
  `role` varchar(16) NOT NULL COMMENT '角色 MARKET_PM|RD_PM（固定不可跨 B7）',
  `member_type` varchar(16) DEFAULT NULL COMMENT '主项目PRIMARY|附加ADDITIONAL（P2-4.2，首个活跃绑定=PRIMARY）',
  `approval_ref` varchar(64) DEFAULT NULL COMMENT '评级委员会审批备案编号（绑第N个项目必填，N=allowance.projectCountThreshold）',
  `locked_level` varchar(8) NOT NULL COMMENT '绑定时评级快照（BR-INC-02）',
  `locked_amount` decimal(10,2) NOT NULL COMMENT '锁定月度津贴额',
  `join_date` datetime NOT NULL COMMENT '加入日期',
  `exit_date` datetime DEFAULT NULL COMMENT '退出日期',
  `exit_reason` varchar(32) DEFAULT NULL COMMENT '退出原因 TRANSFER|VOLUNTARY|LOW_PERF|HANDOVER|HANDOVER_ROLLBACK',
  `bonus_eligible` char(1) NOT NULL DEFAULT '1' COMMENT '奖金资格（放弃置 0，BR-INC-09）',
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id` varchar(20) DEFAULT '000000',
  `del_flag` char(1) DEFAULT '0',
  `remark` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_pm_project` (`project_id`),
  KEY `idx_pm_person` (`person_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IPD 项目成员（双PM 绑定+评级快照）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `project_members`
--
-- WHERE:  project_id=9140004

LOCK TABLES `project_members` WRITE;
/*!40000 ALTER TABLE `project_members` DISABLE KEYS */;
INSERT INTO `project_members` VALUES (9170007,9140004,9110003,'MARKET_PM','PRIMARY',NULL,'L3',2000.00,'2026-09-06 18:38:49',NULL,NULL,'1',NULL,NULL,'2026-09-06 18:38:49',NULL,'2026-09-09 00:14:53','000000','0',NULL);
INSERT INTO `project_members` VALUES (9170008,9140004,9110009,'RD_PM','PRIMARY',NULL,'L4',2500.00,'2026-09-06 18:38:49',NULL,NULL,'1',NULL,NULL,'2026-09-06 18:38:49',NULL,'2026-09-09 00:14:53','000000','0',NULL);
/*!40000 ALTER TABLE `project_members` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-25  7:49:37
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
-- Table structure for table `products`
--

DROP TABLE IF EXISTS `products`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `products` (
  `id` bigint NOT NULL COMMENT '主键（雪花）',
  `product_code` varchar(64) DEFAULT NULL COMMENT '产品编码',
  `product_name` varchar(128) NOT NULL COMMENT '产品名称',
  `model_code` varchar(64) DEFAULT NULL COMMENT '在售型号编码（超管导入）',
  `source` varchar(32) NOT NULL DEFAULT 'PM_NEW' COMMENT '来源 ADMIN_IMPORT|PM_NEW|GUEST_OTHER',
  `project_id` bigint DEFAULT NULL COMMENT '关联项目（1:1 唯一）',
  `group_id` bigint DEFAULT NULL COMMENT '归属产品组',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE|INACTIVE',
  `retired_at` datetime DEFAULT NULL COMMENT '产品退市生效时间',
  `retirement_locked` char(1) DEFAULT '0' COMMENT '退市后全产品线只读锁',
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id` varchar(20) DEFAULT '000000',
  `del_flag` char(1) DEFAULT '0',
  `remark` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_products_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IPD 产品（项目与需求上层实体）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `products`
--
-- WHERE:  project_id=9140004

LOCK TABLES `products` WRITE;
/*!40000 ALTER TABLE `products` DISABLE KEYS */;
INSERT INTO `products` VALUES (9130004,'ZK-GATE-TEST','如门禁测试',NULL,'PM_NEW',9140004,9120001,'RETIRED','2026-09-18 16:04:42','0',NULL,NULL,'2026-09-06 18:37:56',-1,'2026-09-18 16:04:42','000000','1','R35 ZK-GATE-TEST 跨表双写清理 20260918。原 remark:ZK-IPD 场景种子（运行态）');
/*!40000 ALTER TABLE `products` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-25  7:49:37
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
-- Table structure for table `contributions`
--

DROP TABLE IF EXISTS `contributions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `contributions` (
  `id` bigint NOT NULL,
  `project_id` bigint NOT NULL,
  `person_id` bigint NOT NULL,
  `market_contribution_rate` decimal(5,4) NOT NULL,
  `market_share` decimal(18,4) DEFAULT NULL COMMENT '市场占比',
  `rd_share` decimal(18,4) DEFAULT NULL COMMENT '研发占比',
  `market_self_initiation` decimal(5,2) DEFAULT NULL COMMENT '市场自评_立项',
  `market_self_innovation` decimal(5,2) DEFAULT NULL COMMENT '市场自评_创新',
  `market_self_launch` decimal(5,2) DEFAULT NULL COMMENT '市场自评_发布',
  `market_self_market_result` decimal(5,2) DEFAULT NULL COMMENT '市场自评_市场结果',
  `market_self_leadership` decimal(5,2) DEFAULT NULL COMMENT '市场自评_领导力',
  `rd_self_initiation` decimal(5,2) DEFAULT NULL COMMENT '研发自评_立项',
  `rd_self_innovation` decimal(5,2) DEFAULT NULL COMMENT '研发自评_创新',
  `rd_self_launch` decimal(5,2) DEFAULT NULL COMMENT '研发自评_发布',
  `rd_self_market_result` decimal(5,2) DEFAULT NULL COMMENT '研发自评_市场结果',
  `rd_self_leadership` decimal(5,2) DEFAULT NULL COMMENT '研发自评_领导力',
  `tier_coefficient` decimal(5,2) DEFAULT NULL COMMENT '档次系数',
  `market_comment` varchar(1000) DEFAULT NULL COMMENT '市场评语',
  `rd_comment` varchar(1000) DEFAULT NULL COMMENT '研发评语',
  `leader_id` bigint DEFAULT NULL COMMENT '上级领导ID',
  `leader_decision` varchar(32) DEFAULT NULL COMMENT '上级决定',
  `leader_decided_at` datetime DEFAULT NULL COMMENT '上级决定时间',
  `leader_opinion` varchar(1000) DEFAULT NULL COMMENT '上级意见',
  `submitted_at` datetime DEFAULT NULL COMMENT '提交时间',
  `rd_contribution_rate` decimal(5,4) GENERATED ALWAYS AS ((1.0000 - `market_contribution_rate`)) STORED,
  `self_evaluation` json DEFAULT NULL,
  `leader_evaluation` json DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT',
  `archive_version` int DEFAULT NULL,
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id` varchar(20) DEFAULT '000000',
  `del_flag` char(1) DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_contrib_project_person` (`project_id`,`person_id`,`tenant_id`,`del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='贡献度评定';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `contributions`
--
-- WHERE:  project_id=9140004

LOCK TABLES `contributions` WRITE;
/*!40000 ALTER TABLE `contributions` DISABLE KEYS */;
INSERT INTO `contributions` (`id`, `project_id`, `person_id`, `market_contribution_rate`, `market_share`, `rd_share`, `market_self_initiation`, `market_self_innovation`, `market_self_launch`, `market_self_market_result`, `market_self_leadership`, `rd_self_initiation`, `rd_self_innovation`, `rd_self_launch`, `rd_self_market_result`, `rd_self_leadership`, `tier_coefficient`, `market_comment`, `rd_comment`, `leader_id`, `leader_decision`, `leader_decided_at`, `leader_opinion`, `submitted_at`, `self_evaluation`, `leader_evaluation`, `status`, `archive_version`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `tenant_id`, `del_flag`) VALUES (2097198454441791490,9140004,900103,0.6500,0.6500,0.3500,80.00,80.00,80.00,80.00,80.00,NULL,NULL,NULL,NULL,NULL,0.80,'P3-6 真活验证自评 2026-09-08',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'DRAFT',NULL,-1,-1,'2026-09-08 13:40:50',-1,'2026-09-08 23:38:47','000000','0');
/*!40000 ALTER TABLE `contributions` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-25  7:49:37
