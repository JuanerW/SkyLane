-- SkyLane 第二阶段索引 (Flyway V3)
-- 设计说明见 docs/design.md 8.6。V2 已合并，按规则不再修改，索引放在这里。

-- 运营方后台按设备查订单（/ops/orders、/ops/metrics，D29）
CREATE INDEX order_device_created_idx ON delivery_order (device_id, created_at DESC);
-- 后台指标按时间范围统计
CREATE INDEX order_created_idx ON delivery_order (created_at);
-- 按时间顺序找设备空档（D42）、设备排班页（/ops/devices/{id}/schedule）
CREATE INDEX device_block_active_idx ON device_block (device_id, starts_at) WHERE released_at IS NULL;
-- 定时把过期的待确认操作标成 expired（D36）
CREATE INDEX agent_action_pending_idx ON agent_action (expires_at) WHERE status = 'pending';
