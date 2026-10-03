import com.mysql.cj.jdbc.MysqlDataSource;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.ruoyi.workflow.entity.*;
import org.ruoyi.workflow.mapper.WorkflowCheckpointMapper;
import org.ruoyi.workflow.dto.workflow.WfRuntimeNodeDto;
import org.ruoyi.workflow.workflow.*;
import org.ruoyi.workflow.workflow.checkpoint.*;
import org.ruoyi.workflow.workflow.node.AbstractWfNode;
import org.ruoyi.workflow.workflow.data.NodeIOData;
import java.sql.*;
import java.lang.reflect.Proxy;
import java.util.*;

/** Local synthetic read-only runners. Real JDBC rows/leases and production plan/saver; not HTTP Engine acceptance. */
public class WorkflowRecoveryDbProbe {
    static MysqlDataSource ds;
    static String thread;
    static long runtimeId;
    static List<WorkflowNode> nodes = new ArrayList<>();
    static WfState memory;
    static Connection connection() throws SQLException { return ds.getConnection(); }
    static long insert(String sql,Object... values) throws SQLException {
        try(var c=connection();var q=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) {
            for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);q.executeUpdate();
            try(var r=q.getGeneratedKeys()){return r.next()?r.getLong(1):0;}
        }
    }
    static void check(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
    static AbstractWfNode restored(WorkflowNode node,WfNodeState state) {
        return new AbstractWfNode(null,node,memory,state) { public NodeProcessResult onProcess(){throw new UnsupportedOperationException("probe never processes abstract node");} };
    }
    static void setup(boolean initial,boolean effect) throws Exception {
        if(initial) {
            long workflow=insert("INSERT INTO t_workflow(uuid,title,remark,is_enable) VALUES(?,?,?,0)",thread,"f-recovery-probe","synthetic local recovery acceptance; no external calls");
            runtimeId=insert("INSERT INTO t_workflow_runtime(uuid,workflow_id,status_remark) VALUES(?,?,?)",thread,workflow,"f-recovery-probe");
            for(String id:List.of("A","B","C")) {
                String nodeUuid=thread.substring(0,29)+id;
                insert("INSERT INTO t_workflow_node(uuid,workflow_id,workflow_component_id,title,input_config,node_config,remark) VALUES(?,?,?,?,?,?,?)",nodeUuid,workflow,id.equals("B")&&effect?39:17,"f-recovery-probe-"+id,"{}","{}","synthetic read-only runner");
            }
        }
        try(var c=connection();var q=c.prepareStatement("SELECT r.id,n.id,n.uuid,n.workflow_component_id,n.title FROM t_workflow_runtime r JOIN t_workflow_node n ON n.workflow_id=r.workflow_id WHERE r.uuid=? AND n.remark='synthetic read-only runner' ORDER BY n.id")) {
            q.setString(1,thread);try(var r=q.executeQuery()){while(r.next()) {runtimeId=r.getLong(1);var n=new WorkflowNode();n.setId(r.getLong(2));n.setUuid(r.getString(3));n.setWorkflowComponentId(r.getLong(4));n.setTitle(r.getString(5));nodes.add(n);}}
        }
        check(nodes.size()==3,"probe fixture missing");
        memory=new WfState(null,List.of(),thread,0L,null,null,null);
        try(var c=connection();var q=c.prepareStatement("SELECT id,uuid,node_id,output FROM t_workflow_runtime_node WHERE workflow_runtime_id=? AND status=3 AND is_deleted=0 ORDER BY id")) {
            q.setLong(1,runtimeId);try(var r=q.executeQuery()){while(r.next()) {
                WorkflowNode n=nodes.stream().filter(v->{try{return v.getId()==r.getLong(3);}catch(SQLException e){throw new IllegalStateException(e);}}).findFirst().orElseThrow();
                var state=new WfNodeState();state.setUuid(r.getString(2));state.setOutputs(new ArrayList<>(List.of(NodeIOData.createByText("output","",r.getString(4)))));
                memory.getCompletedNodes().add(restored(n,state));var dto=new WfRuntimeNodeDto();dto.setId(r.getLong(1));dto.setUuid(r.getString(2));dto.setNodeId(n.getId());memory.getRuntimeNodes().add(dto);
            }}
        }
    }
    static WorkflowCheckpointMapper mapper() {
        return (WorkflowCheckpointMapper)Proxy.newProxyInstance(WorkflowCheckpointMapper.class.getClassLoader(),new Class[]{WorkflowCheckpointMapper.class},(p,m,a)-> {
            if(m.getName().equals("insert")) {var row=(WorkflowCheckpoint)a[0];insert("INSERT INTO t_workflow_checkpoint(thread_id,checkpoint_id,node_id,next_node_id,state_json) VALUES(?,?,?,?,?)",row.getThreadId(),row.getCheckpointId(),row.getNodeId(),row.getNextNodeId(),row.getStateJson());return 1;}
            if(m.getName().equals("selectList")) {
                List<WorkflowCheckpoint> result=new ArrayList<>();try(var c=connection();var q=c.prepareStatement("SELECT state_json FROM t_workflow_checkpoint WHERE thread_id=? AND is_deleted=0 ORDER BY id")){q.setString(1,thread);try(var r=q.executeQuery()){while(r.next()){var row=new WorkflowCheckpoint();row.setStateJson(r.getString(1));result.add(row);}}}return result;
            }
            throw new UnsupportedOperationException("probe mapper: "+m.getName());
        });
    }
    public static void main(String[] args) throws Exception {
        thread=args[0];boolean initial=args[1].equals("fail"),effect=args[2].equals("effect");
        check(thread.startsWith("fprobe")&&thread.length()==32,"invalid local probe identifier");
        ds=new MysqlDataSource();ds.setURL("jdbc:mysql://127.0.0.1:13306/ipd_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");ds.setUser(System.getenv("IPD_PROBE_DB_USER"));ds.setPassword(System.getenv("IPD_PROBE_DB_PASSWORD"));
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""),WorkflowCheckpoint.class);
        setup(initial,effect);
        var saver=new JdbcCheckpointSaver(mapper(),ds) {
            @Override public synchronized void put(WorkflowCheckpointConfig config,WorkflowCheckpointState cp) throws Exception {
                if(initial&&((Collection<?>)cp.getState().get("completed")).contains(nodes.get(1).getUuid()))throw new SQLException("PROBE checkpoint fault after output commit");
                super.put(config,cp);
            }
        };
        List<WorkflowEdge> edges=new ArrayList<>();for(int i=0;i<2;i++){var e=new WorkflowEdge();e.setSourceNodeUuid(nodes.get(i).getUuid());e.setTargetNodeUuid(nodes.get(i+1).getUuid());edges.add(e);}
        var component=new WorkflowComponent();component.setId(39L);component.setName("MailSend");
        List<String> calls=new ArrayList<>();
        var graph=new WorkflowGraphBuilder(List.of(component),nodes,edges,(node,state)-> {
            String name=node.getTitle().substring(node.getTitle().length()-1);calls.add(name);
            if(!initial&&name.equals("B"))check(memory.getNodeStateByNodeUuid(node.getUuid()).isEmpty(),"uncommitted B polluted retry");
            if(name.equals("C"))check(memory.getIOByNodeUuid(nodes.get(1).getUuid()).get(0).valueToString().contains("retry-B"),"C consumed old attempt");
            state.setOutputs(new ArrayList<>(List.of(NodeIOData.createByText("output","",(initial?"old-":"retry-")+name))));
            long id;
            try {id=insert("INSERT INTO t_workflow_runtime_node(uuid,workflow_runtime_id,node_id,status,status_remark) VALUES(?,?,?,?,?)",state.getUuid(),runtimeId,node.getId(),1,"f-recovery-probe");}
            catch(SQLException error){throw new IllegalStateException(error);}
            var dto=new WfRuntimeNodeDto();dto.setId(id);dto.setUuid(state.getUuid());dto.setNodeId(node.getId());memory.getRuntimeNodes().add(dto);memory.getCompletedNodes().add(restored(node,state));return Map.of();
        },memory).build(nodes.get(0));
        boolean failed=false;String failure="";
        try {graph.execute(saver,thread,!initial,Map.of(),node->{var dto=memory.getRuntimeNodeByNodeUuid(node);check(dto!=null,"attempt missing");String output=memory.getNodeStateByNodeUuid(node).orElseThrow().getOutputs().get(0).valueToString();try{insert("UPDATE t_workflow_runtime_node SET output=JSON_OBJECT('output',?),status=3 WHERE id=?",output,dto.getId());}catch(SQLException e){throw new IllegalStateException(e);}});}catch(Exception e){failed=true;failure=e.getMessage();}
        check(failed==(initial||effect),"unexpected execution outcome");
        if(!initial&&effect)check(calls.isEmpty()&&failure.contains("副作用"),"unknown effect replayed");
        if(!initial&&!effect)check(calls.equals(List.of("B","C")),"committed A reran or frontier wrong");
        var cp=saver.get(WorkflowCheckpointConfig.builder().threadId(thread).build()).orElseThrow();
        if(initial)check(cp.getState().get("completed").equals(List.of(nodes.get(0).getUuid())),"checkpoint committed failed B");
        int attempts=0;try(var c=connection();var q=c.prepareStatement("SELECT COUNT(*) FROM t_workflow_runtime_node WHERE workflow_runtime_id=? AND node_id=? AND status=3 AND is_deleted=0")){q.setLong(1,runtimeId);q.setLong(2,nodes.get(1).getId());try(var r=q.executeQuery()){r.next();attempts=r.getInt(1);}}
        check(attempts==(initial||effect?1:2),"attempt history lost or duplicated");
        System.out.println("PROBE_RESULT thread="+thread+" phase="+args[1]+" effect="+effect+" runtimeId="+runtimeId+" calls="+calls+" failed="+failed+" successfulBAttempts="+attempts);
    }
}
