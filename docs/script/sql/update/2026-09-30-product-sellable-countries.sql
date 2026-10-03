-- 可销售国家。按国家建目录，来源是官网语言/区域入口。
-- 中国站产品只挂中国。区域站（国际、欧洲、拉丁美洲、中东、西非）不建为国家。
-- 销售地图上的城市是网点，不写入。重复执行不覆盖已有国家行，不改产品归属。

CREATE TABLE IF NOT EXISTS market_countries
(
    id          bigint       not null comment '稳定主键',
    country_code varchar(8)  not null comment '国家代码',
    country_name varchar(64) not null comment '国家名称',
    site_url    varchar(255) null comment '官网该国家入口',
    source      varchar(64)  not null comment '来源',
    create_by   bigint       null,
    create_time datetime     null default CURRENT_TIMESTAMP,
    update_by   bigint       null,
    update_time datetime     null default CURRENT_TIMESTAMP on update CURRENT_TIMESTAMP,
    tenant_id   varchar(20)  not null default '000000',
    del_flag    char(1)      not null default '0',
    remark      varchar(500) null,
    primary key (id),
    unique key uk_market_countries_code (tenant_id, country_code, del_flag)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_general_ci comment='可销售国家目录';

CREATE TABLE IF NOT EXISTS product_sellable_countries
(
    id           bigint       not null comment '稳定主键',
    product_id   bigint       not null comment '产品 ID',
    country_code varchar(8)   not null comment '国家代码',
    country_name varchar(64)  not null comment '国家名称',
    evidence_url varchar(512) null comment '证明该国在售的页面',
    source       varchar(64)  not null comment '来源',
    create_by    bigint       null,
    create_time  datetime     null default CURRENT_TIMESTAMP,
    tenant_id    varchar(20)  not null default '000000',
    del_flag     char(1)      not null default '0',
    remark       varchar(500) null,
    primary key (id),
    unique key uk_product_sellable_country (tenant_id, product_id, country_code, del_flag)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_general_ci comment='产品可销售国家';

INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000001, 'CN', '中国', 'https://www.zkteco.com/cn/', 'zkteco-cn-20260930', '000000', '0', 900101, '中国站产品中心'
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='CN' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000002, 'HK', '中国香港', 'http://www.zkteco.com.hk/', 'zkteco-cn-20260930', '000000', '0', 900101, '官网单独入口，不并入中国站产品'
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='HK' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000003, 'AR', '阿根廷', 'http://www.zkteco.com.ar/', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='AR' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000004, 'BD', '孟加拉', 'https://www.zkteco.com.bd/', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='BD' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000005, 'BR', '巴西', 'https://www.zkteco.com.br/', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='BR' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000006, 'CL', '智利', 'https://www.zkteco.cl/', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='CL' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000007, 'EG', '埃及', 'https://zkteco.eg/', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='EG' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000008, 'IN', '印度', 'http://www.zkteco.in/', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='IN' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000009, 'ID', '印度尼西亚', 'http://www.zkteco.co.id', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='ID' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000010, 'IE', '爱尔兰', 'http://www.zkteco.eu', 'zkteco-cn-20260930', '000000', '0', 900101, '官网入口挂在欧洲站，产品未逐条核对'
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='IE' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000011, 'JP', '日本', 'http://www.zkteco.jp/', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='JP' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000012, 'KE', '肯尼亚', 'https://zkteco-ea.com/', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='KE' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000013, 'KR', '韩国', 'http://www.zkteco.kr/', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='KR' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000014, 'MY', '马来西亚', 'http://www.zkteco.com.my/', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='MY' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000015, 'MX', '墨西哥', 'http://www.zktecolatinoamerica.com/', 'zkteco-cn-20260930', '000000', '0', 900101, '官网入口挂在拉丁美洲站，产品未逐条核对'
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='MX' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000016, 'NG', '尼日利亚', 'https://www.zkteco-wa.com/', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='NG' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000017, 'PH', '菲律宾', 'http://zkteco.ph/', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='PH' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000018, 'PE', '秘鲁', 'https://www.zkteco.com.pe/', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='PE' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000019, 'SA', '沙特阿拉伯', 'https://zkteco.sa/', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='SA' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000020, 'ZA', '南非', 'http://www.zkteco.co.za', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='ZA' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000021, 'TH', '泰国', 'http://www.zkteco.co.th', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='TH' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000022, 'TR', '土耳其', 'http://www.zkteknoloji.com.tr', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='TR' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000023, 'US', '美国', 'https://www.zktecousa.com', 'zkteco-cn-20260930', '000000', '0', 900101, '另有入口 http://www.zktechnology.com ，仍记一个国家'
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='US' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000024, 'VN', '越南', 'http://zkteco.vn', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='VN' AND tenant_id='000000' AND del_flag='0');
INSERT INTO market_countries (id, country_code, country_name, site_url, source, tenant_id, del_flag, create_by, remark)
SELECT 2026093001000000025, 'PK', '巴基斯坦', 'https://www.zkteco.com.pk/', 'zkteco-cn-20260930', '000000', '0', 900101, NULL
WHERE NOT EXISTS (SELECT 1 FROM market_countries WHERE country_code='PK' AND tenant_id='000000' AND del_flag='0');

-- 仅当在售型号与中国站 url_key 完全一致时挂中国。不挂测试产品和未对上的抽样。
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000001, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/duomotaishengwushibiezhinengmenjinzhongduan/AI302', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='AI302' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000002, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/menjindianyuan/AP305', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='AP305' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000003, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/duomotaishengwushibiezhinengkaoqinzhongduan/BK100', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='BK100' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000004, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/faqiaqi/CR20M', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='CR20M' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000005, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/shepinkashibiezhinengmenjinzhongduan/SC102', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='SC102' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000006, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/zhinengsangunzha/TS100', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='TS100' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000007, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/zhiwenshibiezhinengkaoqinzhongduan/TX638', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='TX638' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000008, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/zhiwenshibiezhinengkaoqinzhongduan/xu200', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='XU200' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000009, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/saomiaoshebeizhongduan/ZKS10', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='ZKS10' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000010, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/saomiaoshebeizhongduan/ZKS20B', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='ZKS20B' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000011, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/hunheshengwushibiezhinengsuo/ZM400', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='ZM400' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000012, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/zaixianshizhinengxiaofeizhongduan/ZTHP70', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='ZTHP70' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000013, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/zhiwenshibiezhinengmenjinzhongduan/iClock1000', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='iClock1000' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000014, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/zhinengyunzhongduan/xFace100', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='xFace100' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000015, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/duomotaishengwushibiezhinengmenjinzhongduan/xFace60', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='xFace60' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
INSERT INTO product_sellable_countries (id, product_id, country_code, country_name, evidence_url, source, tenant_id, del_flag, create_by)
SELECT 2026093003000000016, p.id, 'CN', '中国', 'https://www.zkteco.com/cn/mianbushibiezhinengmenjinzhongduan/xFace701', 'zkteco-cn-20260930', '000000', '0', 900101
FROM products p
WHERE p.del_flag='0' AND p.tenant_id='000000' AND p.model_code='xFace701' AND p.product_code LIKE 'wb10-%'
AND NOT EXISTS (SELECT 1 FROM product_sellable_countries s WHERE s.product_id=p.id AND s.country_code='CN' AND s.tenant_id='000000' AND s.del_flag='0');
