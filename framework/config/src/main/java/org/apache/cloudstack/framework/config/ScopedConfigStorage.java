// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.framework.config;

import org.apache.cloudstack.framework.config.ConfigKey.Scope;

import com.cloud.utils.Pair;

/**
 *
 * This method is used by individual storage for configuration
 *
 */
public interface ScopedConfigStorage {
    Scope getScope();

    String getConfigValue(long id, String key);

    default String getConfigValue(long id, ConfigKey<?> key) {
        return getConfigValue(id, key.key());
    }
    default Pair<Scope, Long> getParentScope(long id) {
        return null;
    }
}
