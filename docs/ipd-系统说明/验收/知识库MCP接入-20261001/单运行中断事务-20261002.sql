SET time_zone='+08:00';
START TRANSACTION;
SELECT id,status,version FROM ipd_agent_run WHERE id=2105935561859538946 AND project_id=9140005 AND person_id=900103 AND agent_id='ipd_project_agent' FOR UPDATE;
SET @next_seq=(SELECT COALESCE(MAX(seq),0)+1 FROM ipd_agent_run_event WHERE run_id=2105935561859538946);
INSERT INTO ipd_agent_run_event (id,tenant_id,run_id,seq,event_type,payload,del_flag,create_time,update_time)
SELECT UUID_SHORT(),tenant_id,id,@next_seq,'ERROR',JSON_OBJECT('errorCode','PROCESS_INTERRUPTED','message','本机服务异常，运行已中断，请重新发起','maintenanceEvidence','异常进程停止-20261002.json','previousPid',63087),'0',NOW(),NOW()
FROM ipd_agent_run WHERE id=2105935561859538946 AND project_id=9140005 AND person_id=900103 AND agent_id='ipd_project_agent' AND status='RUNNING' AND del_flag='0';
SET @event_inserted=ROW_COUNT();
UPDATE ipd_agent_run SET status='FAILED',error_code='PROCESS_INTERRUPTED',finished_at=NOW(),update_time=NOW(),version=version+1 WHERE id=2105935561859538946 AND status='RUNNING' AND @event_inserted=1;
SELECT @event_inserted AS terminalEventsInserted,ROW_COUNT() AS runsClosed;
COMMIT;
SELECT id,status,error_code FROM ipd_agent_run WHERE id=2105935561859538946;
SELECT seq,event_type,JSON_UNQUOTE(JSON_EXTRACT(payload,'$.errorCode')) AS error_code FROM ipd_agent_run_event WHERE run_id=2105935561859538946 ORDER BY seq DESC LIMIT 1;
