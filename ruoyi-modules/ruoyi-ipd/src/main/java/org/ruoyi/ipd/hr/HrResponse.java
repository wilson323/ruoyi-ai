package org.ruoyi.ipd.hr;

import java.util.List;

/**
 * HR 平台标准响应包装（FA-HR-Sync·2026-09-21 R149）。
 *
 * <p>{@code code=0} 成功；其他见 HR 文档第二章异常码（1000/1001/.../8008/9999）。
 * <p>{@code data} 可能为 List（数组）或 Object，调用方按接口类型断言。
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

    /** HR 业务失败 → 抛异常，把 msg 透出去。 */
    public void ensureOk() {
        if (code != 0) {
            throw new HrApiException(code, msg != null ? msg : "HR 业务失败", null);
        }
    }

    // ───── DTO：从 data.BODY 数组抽取的扁平行（FA-HR-Sync-4 增量实现）───

    /** HR 推过来的人员一行（仅保留本期要落 persons 表的字段；其他暂存 HR 原值待回查）。 */
    public static class PersonRow {
        public String pernr;            // PERNR 员工编号 → persons.employee_no
        public BasicInfo basicInfo;     // BASIC_INFO
        public List<BankRow> bankList;  // 本期不接（BANKLIST/COSTCENTER 排除范围）
        public List<Object> costCenterInfoList;
        public Object idInfo;           // 证件，本期不接
    }
    public static class BasicInfo {
        public String nachn;            // 姓名
        public String rufnm;            // 英文名
        public String gesch;            // 性别 1/2
        public String natio;            // 国籍
        public String gbdat;            // 出生日期 yyyy-MM-dd
        public String famst;            // 婚姻
        public String liveAdress;       // 现住址
        public String workAdress;       // 工作地址
        public String phone;            // 手机
        public String emailPer;         // 个人邮箱
        public String emailCom;         // 企业邮箱
        public String hireDate;         // 入职日期
        public String servicestartdate;
        public String regulardate;      // 转正日期
        public String effectdatemovent;
        public String leaveDate;        // 离职日期
        public String bukrs;            // 公司代码
        public String contractsubject;  // 合同主体
        public String persg;            // 员工组 Z004
        public String orgeh;            // 部门编码 → persons.hr_orgeh
        public String deptname;         // 部门名称
        public String plans;            // 职位编码
        public String positionname;
        public String externalposition;
        public String orgpath;          // 组织全称
        public String empcategory;
        public String zzwtpdj;
        public String positiongrade;
        public String languagecode;
        public String bankn;            // 主银行卡号 → persons.bank_account
        public String bankl;
        public String stat2;            // 3 在职 / 0 离职 → employment_status
        public String leaveFlag;        // X 离职
        public String delFlag;          // X 删除
        public String kostl;
        public String kostlT;
        public String supervisorno;
        public String supervisorname;
        public String lifnr;
        public String fileurl;
        public String certificate;      // 最高学历
        public String insitute;         // 毕业院校
        public String lineOfStudy;      // 专业
        public String qualificationlevel;
    }
    public static class BankRow {
        public String bankcode;
        public String bankname;
        public String bankacno;
        public String bankacholder;
        public String bankcardusage;
        public String cardflag;
        public String banknation;
    }

    /** HR 推过来的组织一行。 */
    public static class OrgRow {
        public String orgeh;            // 组织编码
        public String stext;            // 组织全称
        public String short;            // 组织简称
        public String begda;            // 有效起
        public String endda;            // 有效止
        public String orgehPup;         // 上级组织编码
        public String bmfzr;            // 部门负责人 PERNR
        public String zbmcj;            // 层级
        public String delFlag;          // 失效
        public String expirationflag;
    }

    /** 全量组织/人员同步的入参构造器。 */
    public static class SyncBody {
        public SyncBody() {
            head = new Head();
            body = new java.util.ArrayList<>();
            body.add(new SyncInputRow("ALL", "20230612", "20230612"));
        }
        public Head head;
        public List<SyncInputRow> body;
        public static class Head {
            public String intfId = "";
            public String srcSystem = "";
            public String destSystem = "";
            public String srcMsgid = "";
            public String backup1 = "";
            public String backup2 = "";
        }
        public static class SyncInputRow {
            public String inputTyp;       // ALL | NEW
            public String begda;          // yyyyMMdd
            public String endda;          // yyyyMMdd
            public SyncInputRow(String t, String b, String e) { this.inputTyp=t; this.begda=b; this.endda=e; }
        }
        public static SyncBody full() {
            return new SyncBody();
        }
        public static SyncBody incremental(String todayYyyyMmDd) {
            SyncBody sb = new SyncBody();
            sb.body.clear();
            sb.body.add(new SyncInputRow("NEW", todayYyyyMmDd, todayYyyyMmDd));
            return sb;
        }
    }
}
