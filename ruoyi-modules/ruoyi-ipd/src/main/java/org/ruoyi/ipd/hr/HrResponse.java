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
 * <p>DTO 字段命名对齐 HR 文档大写驼峰；下游 Service 层做 HR→persons 字段映射。
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
        public String rufnm;
        @com.fasterxml.jackson.annotation.JsonProperty("GESCH")
        public String gesch;            // 性别 1/2
        public String natio;
        @com.fasterxml.jackson.annotation.JsonProperty("GBDAT")
        public String gbdat;            // 出生日期
        public String famst;
        public String liveAdress;
        public String workAdress;
        public String phone;
        public String emailPer;
        @com.fasterxml.jackson.annotation.JsonProperty("EMAIL_COM")
        public String emailCom;
        @com.fasterxml.jackson.annotation.JsonProperty("HIRE_DATE")
        public String hireDate;         // 入职日期
        public String servicestartdate;
        public String regulardate;
        public String effectdatemovent;
        @com.fasterxml.jackson.annotation.JsonProperty("LEAVE_DATE")
        public String leaveDate;
        public String bukrs;
        public String contractsubject;
        public String persg;
        @com.fasterxml.jackson.annotation.JsonProperty("ORGEH")
        public String orgeh;            // 部门编码（关联 hr_organizations）
        public String deptname;
        @com.fasterxml.jackson.annotation.JsonProperty("PLANS")
        public String plans;
        @com.fasterxml.jackson.annotation.JsonProperty("POSITIONNAME")
        public String positionname;
        public String externalposition;
        public String orgpath;
        public String empcategory;
        public String zzwtpdj;
        public String positiongrade;
        public String languagecode;
        public String bankn;
        public String bankl;
        @com.fasterxml.jackson.annotation.JsonProperty("STAT2")
        public String stat2;            // 3=在职 / 0=离职 → employment_status
        public String leaveFlag;        // X=离职
        @com.fasterxml.jackson.annotation.JsonProperty("DEL_FLAG")
        public String delFlag;          // X=删除
        public String kostl;
        public String kostlT;
        public String supervisorno;
        public String supervisorname;
        public String lifnr;
        public String fileurl;
        public String certificate;
        public String insitute;
        public String lineOfStudy;
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

    public static class SyncBody {
        public Head head = new Head();
        public List<SyncInputRow> body = new ArrayList<>();

        public static class Head {
            public String intfId = "";
            public String srcSystem = "";
            public String destSystem = "";
            public String srcMsgid = "";
            public String backup1 = "";
            public String backup2 = "";
        }

        public static class SyncInputRow {
            public String inputTyp;   // ALL | NEW
            public String begda;      // yyyyMMdd
            public String endda;      // yyyyMMdd
            public SyncInputRow() { }
            public SyncInputRow(String t, String b, String e) { inputTyp=t; begda=b; endda=e; }
        }

        public static SyncBody full() {
            SyncBody sb = new SyncBody();
            sb.body.add(new SyncInputRow("ALL", "20230612", "20230612"));
            return sb;
        }

        public static SyncBody incremental(String todayYyyyMmDd) {
            SyncBody sb = new SyncBody();
            sb.body.add(new SyncInputRow("NEW", todayYyyyMmDd, todayYyyyMmDd));
            return sb;
        }

        /** 单人同步入参（R149-v1 syncOne/upsertOne 用；HR §3.2.1 入参 pernr 作为查询字段）。 */
        public static SyncBody single(String pernr) {
            SyncBody sb = new SyncBody();
            sb.head.backup1 = pernr;
            sb.body.add(new SyncInputRow("ALL", "20230612", "20230612"));
            return sb;
        }

        /** 单点组织同步入参（R149-v1 org 单点回填；HR §3.2.2 入参 orgeh 作为查询字段）。 */
        public static SyncBody org(String orgeh) {
            SyncBody sb = new SyncBody();
            sb.head.backup2 = orgeh;
            sb.body.add(new SyncInputRow("ALL", "20230612", "20230612"));
            return sb;
        }
    }
}
