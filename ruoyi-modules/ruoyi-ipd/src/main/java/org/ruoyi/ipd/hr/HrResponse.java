package org.ruoyi.ipd.hr;

import java.util.ArrayList;
import java.util.List;

/**
 * HR 平台标准响应 + DTO（FA-HR-Sync·2026-09-21 R149）。
 *
 * <p>{@code code=0} 成功；其他见 HR 文档异常码（1000/1001/.../8008/9999）。
 * <p>本期只解析 HR §3.2.1.8（人员）/ §3.2.2.8（组织）出参示例；
 * BANKLIST / COSTCENTER / JOBTITLE 不接（用户 2026-09-21 明确排除）。
 *
 * <p>DTO 字段命名对齐 HR 文档的大写下划线键名（如 PERNR / BASIC_INFO / INPUT_TYP）；
 * 由 Jackson @JsonProperty 显式锚定，序列化与反序列化共用同一契约。
 */
public class HrResponse<T> {

    private int code;
    private String msg;
    private T data;

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
    public boolean isOk() { return code == 0; }

    // ───── 人员行（HR §3.2.1.8） ─────

    public static class PersonRow {
        @com.fasterxml.jackson.annotation.JsonProperty("PERNR")
        public String pernr;            // 员工编号 → persons.employee_no
        @com.fasterxml.jackson.annotation.JsonProperty("BASIC_INFO")
        public BasicInfo basicInfo;
        @com.fasterxml.jackson.annotation.JsonProperty("ID_INFO")
        public Object idInfo;           // 证件（不接）
        @com.fasterxml.jackson.annotation.JsonProperty("COSTCENTER_INFO_LIST")
        public List<Object> costCenterInfoList;  // 成本中心（不接）
        @com.fasterxml.jackson.annotation.JsonProperty("BANKLIST")
        public List<Object> bankList;    // 银行（不接）
    }

    public static class BasicInfo {
        @com.fasterxml.jackson.annotation.JsonProperty("NACHN")
        public String nachn;            // 姓名 → persons.name
        @com.fasterxml.jackson.annotation.JsonProperty("RUFNM")
        public String rufnm;
        @com.fasterxml.jackson.annotation.JsonProperty("GESCH")
        public String gesch;            // 性别 1/2
        @com.fasterxml.jackson.annotation.JsonProperty("NATIO")
        public String natio;
        @com.fasterxml.jackson.annotation.JsonProperty("GBDAT")
        public String gbdat;            // 出生日期
        @com.fasterxml.jackson.annotation.JsonProperty("FAMST")
        public String famst;
        @com.fasterxml.jackson.annotation.JsonProperty("LIVE_ADRESS")
        public String liveAdress;
        @com.fasterxml.jackson.annotation.JsonProperty("WORK_ADRESS")
        public String workAdress;
        @com.fasterxml.jackson.annotation.JsonProperty("PHONE")
        public String phone;
        @com.fasterxml.jackson.annotation.JsonProperty("EMAIL_PER")
        public String emailPer;
        @com.fasterxml.jackson.annotation.JsonProperty("EMAIL_COM")
        public String emailCom;
        @com.fasterxml.jackson.annotation.JsonProperty("HIRE_DATE")
        public String hireDate;         // 入职日期
        @com.fasterxml.jackson.annotation.JsonProperty("SERVICESTARTDATE")
        public String servicestartdate;
        @com.fasterxml.jackson.annotation.JsonProperty("REGULARDATE")
        public String regulardate;
        @com.fasterxml.jackson.annotation.JsonProperty("EFFECTDATEMOVENT")
        public String effectdatemovent;
        @com.fasterxml.jackson.annotation.JsonProperty("LEAVE_DATE")
        public String leaveDate;
        @com.fasterxml.jackson.annotation.JsonProperty("BUKRS")
        public String bukrs;
        @com.fasterxml.jackson.annotation.JsonProperty("CONTRACTSUBJECT")
        public String contractsubject;
        @com.fasterxml.jackson.annotation.JsonProperty("PERSG")
        public String persg;
        @com.fasterxml.jackson.annotation.JsonProperty("ORGEH")
        public String orgeh;            // 部门编码（关联 hr_organizations）
        @com.fasterxml.jackson.annotation.JsonProperty("DEPTNAME")
        public String deptname;
        @com.fasterxml.jackson.annotation.JsonProperty("PLANS")
        public String plans;
        @com.fasterxml.jackson.annotation.JsonProperty("POSITIONNAME")
        public String positionname;
        @com.fasterxml.jackson.annotation.JsonProperty("EXTERNALPOSITION")
        public String externalposition;
        @com.fasterxml.jackson.annotation.JsonProperty("ORGPATH")
        public String orgpath;
        @com.fasterxml.jackson.annotation.JsonProperty("EMPCATEGORY")
        public String empcategory;
        @com.fasterxml.jackson.annotation.JsonProperty("ZZWTPDJ")
        public String zzwtpdj;
        @com.fasterxml.jackson.annotation.JsonProperty("POSITIONGRADE")
        public String positiongrade;
        @com.fasterxml.jackson.annotation.JsonProperty("LANGUAGECODE")
        public String languagecode;
        @com.fasterxml.jackson.annotation.JsonProperty("BANKN")
        public String bankn;
        @com.fasterxml.jackson.annotation.JsonProperty("BANKL")
        public String bankl;
        @com.fasterxml.jackson.annotation.JsonProperty("STAT2")
        public String stat2;            // 3=在职 / 0=离职 → employment_status
        @com.fasterxml.jackson.annotation.JsonProperty("LEAVE_FLAG")
        public String leaveFlag;        // X=离职
        @com.fasterxml.jackson.annotation.JsonProperty("DEL_FLAG")
        public String delFlag;          // X=删除
        @com.fasterxml.jackson.annotation.JsonProperty("KOSTL")
        public String kostl;
        @com.fasterxml.jackson.annotation.JsonProperty("KOSTL_T")
        public String kostlT;
        @com.fasterxml.jackson.annotation.JsonProperty("SUPERVISORNO")
        public String supervisorno;
        @com.fasterxml.jackson.annotation.JsonProperty("SUPERVISORNAME")
        public String supervisorname;
        @com.fasterxml.jackson.annotation.JsonProperty("LIFNR")
        public String lifnr;
        @com.fasterxml.jackson.annotation.JsonProperty("FILEURL")
        public String fileurl;
        @com.fasterxml.jackson.annotation.JsonProperty("CERTIFICATE")
        public String certificate;
        @com.fasterxml.jackson.annotation.JsonProperty("INSITUTE")
        public String insitute;
        @com.fasterxml.jackson.annotation.JsonProperty("LINE_OF_STUDY")
        public String lineOfStudy;
        @com.fasterxml.jackson.annotation.JsonProperty("QUALIFICATIONLEVEL")
        public String qualificationlevel;
    }

    // ───── 组织行（HR §3.2.2.8） ─────

    public static class OrgRow {
        @com.fasterxml.jackson.annotation.JsonProperty("ORGEH")
        public String orgeh;            // 组织编码
        @com.fasterxml.jackson.annotation.JsonProperty("STEXT")
        public String stext;            // 组织全称
        @com.fasterxml.jackson.annotation.JsonProperty("SHORT")
        public String shortName;        // 组织简称
        @com.fasterxml.jackson.annotation.JsonProperty("BEGDA")
        public String begda;            // 有效起
        @com.fasterxml.jackson.annotation.JsonProperty("ENDDA")
        public String endda;            // 有效止
        @com.fasterxml.jackson.annotation.JsonProperty("ORGEH_PUP")
        public String orgehPup;         // 上级组织编码
        @com.fasterxml.jackson.annotation.JsonProperty("BMFZR")
        public String bmfzr;            // 部门负责人 PERNR
        @com.fasterxml.jackson.annotation.JsonProperty("ZBMCJ")
        public String zbmcj;            // 层级
        @com.fasterxml.jackson.annotation.JsonProperty("DELFLAG")
        public String delFlag;
        @com.fasterxml.jackson.annotation.JsonProperty("EXPIRATIONFLAG")
        public String expirationflag;
    }

    // ───── 入参 body 构造器（HR §3.2.1 / §3.2.2 DATA 段） ─────
    //
    // 2026-10-07 修复：HEAD/BODY/INPUT_TYP 等键名必须为大写下划线（对齐官方 demo
    // DataUtil 与 ehr-probe.mjs 实测口径）；序列化路径为 JsonUtil（Jackson），
    // @JsonProperty 注解在此生效（此前 Hutool 序列化不识别 Jackson 注解，输出小驼峰）。

    public static class SyncBody {
        @com.fasterxml.jackson.annotation.JsonProperty("HEAD")
        public Head head = new Head();
        @com.fasterxml.jackson.annotation.JsonProperty("BODY")
        public List<SyncInputRow> body = new ArrayList<>();

        public static class Head {
            @com.fasterxml.jackson.annotation.JsonProperty("INTF_ID")
            public String intfId = "";
            @com.fasterxml.jackson.annotation.JsonProperty("SRC_SYSTEM")
            public String srcSystem = "";
            @com.fasterxml.jackson.annotation.JsonProperty("DEST_SYSTEM")
            public String destSystem = "";
            @com.fasterxml.jackson.annotation.JsonProperty("SRC_MSGID")
            public String srcMsgid = "";
            @com.fasterxml.jackson.annotation.JsonProperty("BACKUP1")
            public String backup1 = "";
            @com.fasterxml.jackson.annotation.JsonProperty("BACKUP2")
            public String backup2 = "";
        }

        public static class SyncInputRow {
            @com.fasterxml.jackson.annotation.JsonProperty("INPUT_TYP")
            public String inputTyp;   // ALL | NEW
            @com.fasterxml.jackson.annotation.JsonProperty("BEGDA")
            public String begda;      // yyyyMMdd
            @com.fasterxml.jackson.annotation.JsonProperty("ENDDA")
            public String endda;      // yyyyMMdd
            public SyncInputRow() { }
            public SyncInputRow(String t, String b, String e) { inputTyp=t; begda=b; endda=e; }
        }

        /**
         * 全量（INPUT_TYP=ALL）。BEGDA/ENDDA 传查询当天：与 ehr-probe.mjs（2026-09-29
         * 真连实测口径）一致；官方 demo 的固定日期为样例值，不作运行口径。
         */
        public static SyncBody full(String todayYyyyMmDd) {
            SyncBody sb = new SyncBody();
            sb.body.add(new SyncInputRow("ALL", todayYyyyMmDd, todayYyyyMmDd));
            return sb;
        }

        /** 增量（INPUT_TYP=NEW + BEGDA/ENDDA=当天 yyyyMMdd，每日 0 点增量口径）。 */
        public static SyncBody incremental(String todayYyyyMmDd) {
            SyncBody sb = new SyncBody();
            sb.body.add(new SyncInputRow("NEW", todayYyyyMmDd, todayYyyyMmDd));
            return sb;
        }

        /** 单人同步入参（R149-v1 syncOne/upsertOne 用；HR §3.2.1 入参 pernr 作为查询字段）。 */
        public static SyncBody single(String pernr, String todayYyyyMmDd) {
            SyncBody sb = new SyncBody();
            sb.head.backup1 = pernr;
            sb.body.add(new SyncInputRow("ALL", todayYyyyMmDd, todayYyyyMmDd));
            return sb;
        }

        /** 单点组织同步入参（R149-v1 org 单点回填；HR §3.2.2 入参 orgeh 作为查询字段）。 */
        public static SyncBody org(String orgeh, String todayYyyyMmDd) {
            SyncBody sb = new SyncBody();
            sb.head.backup2 = orgeh;
            sb.body.add(new SyncInputRow("ALL", todayYyyyMmDd, todayYyyyMmDd));
            return sb;
        }
    }
}
