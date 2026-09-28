-- ============================================================================
-- chat_model_category 字典补值：语音(audio) / 视频(video)   —— 2026-09-28 模型管理盘点
--
-- 背景：模型管理页（前端 views/chat/model/model-modal.vue）已可选 image/audio/video/rerank
--       分类，MediaGenerationController 已按 category=audio/image/video 真实消费
--       （/media/speech、/media/image、/media/video），但字典 chat_model_category 仅有
--       chat/image/vector/rerank 四值（真库 13306 复核一致）——audio/video 缺值导致
--       模型管理列表分类列回显裸值。本脚本补两行字典数据（纯 DML，无 DDL）。
--
-- 执行方式：仓库无 Flyway，本脚本需人工 apply。
-- 状态：★ 待 owner apply ★（apply 前前端 model-modal 已有兜底注入，功能不阻塞）
-- ============================================================================

INSERT IGNORE INTO `sys_dict_data`
  (`dict_code`, `tenant_id`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`,
   `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`,
   `update_by`, `update_time`, `remark`)
VALUES
  (202609281200000001, '000000', 3, '语音', 'audio', 'chat_model_category',
   NULL, 'purple', 'N', 103, 1, NOW(), 1, NOW(), '语音生成/语音转文本模型'),
  (202609281200000002, '000000', 5, '视频', 'video', 'chat_model_category',
   NULL, 'red', 'N', 103, 1, NOW(), 1, NOW(), '文生视频模型');
