-- 小蚕霸王餐刷任务 mini 登录态表（独立于 grab_login_state，避免影响抢单）
DROP TABLE IF EXISTS `lottery_auth`;
CREATE TABLE `lottery_auth`  (
  `id`          INT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id`     INT NOT NULL COMMENT '系统用户ID',
  `name`        VARCHAR(64) NOT NULL COMMENT '别名,如 主账号/小号',
  `silk_id`     INT NOT NULL COMMENT '小蚕 silk_id(请求body + X-Teemo)',
  `user_vayne`  INT NULL DEFAULT NULL COMMENT '小蚕用户id(X-Vayne)',
  `session_id`  VARCHAR(64) NOT NULL COMMENT '会话id(X-Session-Id)',
  `nami`        VARCHAR(32) NULL DEFAULT NULL COMMENT 'X-Nami(可选,默认随机)',
  `raw_headers` TEXT NULL DEFAULT NULL COMMENT '录入的原始抓包 header(留底)',
  `create_time`  DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`  DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`      TINYINT(1) NULL DEFAULT 0 COMMENT '逻辑删除标志',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id` (`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '小蚕霸王餐刷任务 mini 登录态(多组)' ROW_FORMAT = Dynamic;
