-- PENDING OWNER REVIEW: DO NOT EXECUTE. New skill bindings require skill owner decision.
-- These skills are not registered in the runtime capability manifest until approved.
UPDATE ipd_action_skill_map SET skill_names=JSON_ARRAY('technical-feasibility-ipd') WHERE action_code='C05' AND tenant_id='000000' AND del_flag='0' AND skill_names IS NULL;
UPDATE ipd_action_skill_map SET skill_names=JSON_ARRAY('system-architecture-ipd') WHERE action_code='P03' AND tenant_id='000000' AND del_flag='0' AND skill_names IS NULL;
UPDATE ipd_action_skill_map SET skill_names=JSON_ARRAY('hardware-design-plan-ipd') WHERE action_code='P04' AND tenant_id='000000' AND del_flag='0' AND skill_names IS NULL;
UPDATE ipd_action_skill_map SET skill_names=JSON_ARRAY('software-design-plan-ipd') WHERE action_code='P05' AND tenant_id='000000' AND del_flag='0' AND skill_names IS NULL;
UPDATE ipd_action_skill_map SET skill_names=JSON_ARRAY('solution-integration-plan-ipd') WHERE action_code='P06' AND tenant_id='000000' AND del_flag='0' AND skill_names IS NULL;
UPDATE ipd_action_skill_map SET skill_names=JSON_ARRAY('component-supply-assessment-ipd') WHERE action_code='P07' AND tenant_id='000000' AND del_flag='0' AND skill_names IS NULL;
UPDATE ipd_action_skill_map SET skill_names=JSON_ARRAY('project-milestone-plan-ipd') WHERE action_code='P08' AND tenant_id='000000' AND del_flag='0' AND skill_names IS NULL;
UPDATE ipd_action_skill_map SET skill_names=JSON_ARRAY('resource-budget-plan-ipd') WHERE action_code='P09' AND tenant_id='000000' AND del_flag='0' AND skill_names IS NULL;
UPDATE ipd_action_skill_map SET skill_names=JSON_ARRAY('project-risk-plan-ipd') WHERE action_code='P11' AND tenant_id='000000' AND del_flag='0' AND skill_names IS NULL;
UPDATE ipd_action_skill_map SET skill_names=JSON_ARRAY('detailed-design-ipd') WHERE action_code='D01' AND tenant_id='000000' AND del_flag='0' AND skill_names IS NULL;
UPDATE ipd_action_skill_map SET skill_names=JSON_ARRAY('software-unit-test-plan-ipd') WHERE action_code='D04' AND tenant_id='000000' AND del_flag='0' AND skill_names IS NULL;
UPDATE ipd_action_skill_map SET skill_names=JSON_ARRAY('aftersales-repair-plan-ipd') WHERE action_code='V08' AND tenant_id='000000' AND del_flag='0' AND skill_names IS NULL;
