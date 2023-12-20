/*
 * This file is part of HKBusETA.
 *
 * Copyright (C) 2023. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2023. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.loohp.hkbuseta.common.objects

enum class RouteSortMode {

    NORMAL,
    RECENT,
    PROXIMITY;

    companion object {

        private val VALUES = entries.toTypedArray()

    }

    fun nextMode(allowRecentSort: Boolean, allowProximitySort: Boolean): RouteSortMode {
        val next = VALUES[(ordinal + 1) % VALUES.size]
        return if (next.isLegalMode(allowRecentSort, allowProximitySort)) {
            next
        } else {
            next.nextMode(allowRecentSort, allowProximitySort)
        }
    }

    fun isLegalMode(allowRecentSort: Boolean, allowProximitySort: Boolean): Boolean {
        return (allowProximitySort || this != PROXIMITY) && (allowRecentSort || this != RECENT)
    }

}
