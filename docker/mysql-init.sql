-- 首次启动时执行（docker-entrypoint-initdb.d）。
-- chatdata_ask 由 compose 里的 MYSQL_DATABASE 创建，这里补另外两个。
CREATE DATABASE IF NOT EXISTS chatdata_iam  DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS chatdata_demo DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
