-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

DROP PROCEDURE IF EXISTS `cloud`.`IDEMPOTENT_ADD_FOREIGN_KEY`;

CREATE PROCEDURE `cloud`.`IDEMPOTENT_ADD_FOREIGN_KEY` (
	IN in_table_name VARCHAR(200)
    , IN in_key_name VARCHAR(200)
    , IN in_foreign_key VARCHAR(200)
    , IN in_references VARCHAR(1000)
)
BEGIN

    DECLARE CONTINUE HANDLER FOR 1061 BEGIN END; SET @ddl = CONCAT_WS(' ', 'ALTER TABLE ', in_table_name, ' ADD CONSTRAINT ', in_key_name, ' FOREIGN KEY ', in_foreign_key, ' REFERENCES ', in_references, ' ON DELETE CASCADE'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; END;
