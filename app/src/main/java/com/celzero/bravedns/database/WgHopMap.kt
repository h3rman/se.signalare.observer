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

@Entity(tableName = "WgHopMap")
class WgHopMap {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0
    var src: String = ""
    var hop: String = ""
    var isActive: Boolean = false
    var status: String = "" // last known status from tunnel

    override fun toString(): String {
        return "WgHopMap(id=$id, src='$src', hop='$hop', isActive=$isActive, status='$status')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WgHopMap) return false

        if (id != other.id) return false
        if (src != other.src) return false
        if (hop != other.hop) return false
        if (isActive != other.isActive) return false
        if (status != other.status) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + src.hashCode()
        result = 31 * result + hop.hashCode()
        result = 31 * result + isActive.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }

    constructor(id: Int, src: String, hop: String, isActive: Boolean, status: String) {
        this.id = id
        this.src = src
        this.hop = hop
        this.isActive = isActive
        this.status = status
    }
}