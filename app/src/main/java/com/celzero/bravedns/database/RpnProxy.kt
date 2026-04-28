/*
 * Copyright 2024 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.signalare.observer.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "RpnProxy")
class RpnProxy {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
    var name: String = ""
    var configPath: String = "" // can be empty
    var serverResPath: String = "" // can be empty
    var isActive: Boolean = false
    var isLockdown: Boolean = false
    var createdTs: Long = 0L
    var modifiedTs: Long = 0L
    var misc: String = "" // can be empty
    var tunId: String = "" // id assigned while adding the proxy to the tunnel, can be empty
    var latency: Int = 0
    var lastRefreshTime: Long = 0L // last time the proxy was refreshed

    override fun equals(other: Any?): Boolean {
        if (other !is RpnProxy) return false
        if (id != other.id) return false
        if (name != other.name) return false
        return true
    }

    override fun hashCode(): Int {
        var result = this.id.hashCode()
        result += result * 31 + this.name.hashCode()
        return result
    }

    fun copy(isActive: Boolean): RpnProxy {
        return RpnProxy(
            id = this.id,
            name = this.name,
            configPath = this.configPath,
            serverResPath = this.serverResPath,
            isActive = isActive,
            isLockdown = this.isLockdown,
            createdTs = this.createdTs,
            modifiedTs = this.modifiedTs,
            misc = this.misc,
            tunId = this.tunId,
            latency = this.latency,
            lastRefreshTime = System.currentTimeMillis()
        )
    }

    constructor(
        id: Int,
        name: String,
        configPath: String,
        serverResPath: String,
        isActive: Boolean,
        isLockdown: Boolean,
        createdTs: Long,
        modifiedTs: Long,
        misc: String,
        tunId: String,
        latency: Int,
        lastRefreshTime: Long
    ) {
        // Room auto-increments id when its set to zero.
        // A non-zero id overrides and sets caller-specified id instead.
        this.id = id
        this.name = name
        this.configPath = configPath
        this.serverResPath = serverResPath
        this.isActive = isActive
        this.isLockdown = isLockdown
        this.createdTs = createdTs
        this.modifiedTs = modifiedTs
        this.misc = misc
        this.tunId = tunId
        this.latency = latency
        this.lastRefreshTime = lastRefreshTime
    }
}
