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

package com.loohp.hkbuseta.objects

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.loohp.hkbuseta.shared.KMBSubsidiary
import com.loohp.hkbuseta.shared.Registry
import com.loohp.hkbuseta.shared.Registry.ETALineEntry
import com.loohp.hkbuseta.shared.Registry.ETAQueryResult
import com.loohp.hkbuseta.shared.Registry.ETAShortText
import com.loohp.hkbuseta.shared.Shared
import com.loohp.hkbuseta.utils.toHexString


private val bilingualToPrefix = "往" withEn "To "

inline val Operator.name: String get() = name()

inline val Operator.isTrain: Boolean get() = this == Operator.MTR || this == Operator.LRT

fun Operator.getColorHex(routeNumber: String, elseColor: Long): String {
    return getColor(routeNumber, Color(elseColor)).toHexString()
}

fun Operator.getColor(routeNumber: String, elseColor: Color): Color {
    return when (this) {
        Operator.KMB -> if (Shared.getKMBSubsidiary(routeNumber) == KMBSubsidiary.LWB) Color(0xFFF26C33) else Color(0xFFFF4747)
        Operator.CTB -> Color(0xFFFFE15E)
        Operator.NLB -> Color(0xFF9BFFC6)
        Operator.MTR_BUS -> Color(0xFFAAD4FF)
        Operator.GMB -> Color(0xFF36FF42)
        Operator.LRT -> Color(0xFFD3A809)
        Operator.MTR -> when (routeNumber) {
            "AEL" -> Color(0xFF00888E)
            "TCL" -> Color(0xFFF3982D)
            "TML" -> Color(0xFF9C2E00)
            "TKL" -> Color(0xFF7E3C93)
            "EAL" -> Color(0xFF5EB7E8)
            "SIL" -> Color(0xFFCBD300)
            "TWL" -> Color(0xFFE60012)
            "ISL" -> Color(0xFF0075C2)
            "KTL" -> Color(0xFF00A040)
            "DRL" -> Color(0xFFEB6EA5)
            else -> elseColor
        }
        else -> elseColor
    }
}

fun Operator.getLineColor(routeNumber: String, elseColor: Color): Color {
    return if (this == Operator.LRT) when (routeNumber) {
        "505" -> Color(0xFFDA2127)
        "507" -> Color(0xFF00A652)
        "610" -> Color(0xFF551C15)
        "614" -> Color(0xFF00BFF3)
        "614P" -> Color(0xFFF4858E)
        "615" -> Color(0xFFFFDD00)
        "615P" -> Color(0xFF016682)
        "705" -> Color(0xFF73BF43)
        "706" -> Color(0xFFB47AB5)
        "751" -> Color(0xFFF48221)
        "761P" -> Color(0xFF6F2D91)
        else -> getColor(routeNumber, elseColor)
    } else getColor(routeNumber, elseColor)
}

fun Operator.getDisplayRouteNumber(routeNumber: String, shortened: Boolean = false): String {
    return if (this == Operator.MTR) {
        if (shortened && Shared.language == "en") routeNumber else Shared.getMtrLineName(routeNumber, "???")
    } else if (this == Operator.KMB && Shared.getKMBSubsidiary(routeNumber) == KMBSubsidiary.SUNB) {
        "NR".plus(routeNumber)
    } else {
        routeNumber
    }
}

fun Operator.getDisplayName(routeNumber: String, kmbCtbJoint: Boolean, language: String, elseName: String = "???"): String {
    return if (language == "en") when (this) {
        Operator.KMB -> when (Shared.getKMBSubsidiary(routeNumber)) {
            KMBSubsidiary.SUNB -> "Sun Bus"
            KMBSubsidiary.LWB -> if (kmbCtbJoint) "LWB/CTB" else "LWB"
            else -> if (kmbCtbJoint) "KMB/CTB" else "KMB"
        }
        Operator.CTB -> "CTB"
        Operator.NLB -> "NLB"
        Operator.MTR_BUS -> "MTR Bus"
        Operator.GMB -> "GMB"
        Operator.LRT -> "LRT"
        Operator.MTR -> "MTR"
        else -> elseName
    } else when (this) {
        Operator.KMB -> when (Shared.getKMBSubsidiary(routeNumber)) {
            KMBSubsidiary.SUNB -> "陽光巴士"
            KMBSubsidiary.LWB -> if (kmbCtbJoint) "龍運/城巴" else "龍運"
            else -> if (kmbCtbJoint) "九巴/城巴" else "九巴"
        }
        Operator.CTB -> "城巴"
        Operator.NLB -> "嶼巴"
        Operator.MTR_BUS -> "港鐵巴士"
        Operator.GMB -> "專線小巴"
        Operator.LRT -> "輕鐵"
        Operator.MTR -> "港鐵"
        else -> elseName
    }
}

fun Route.resolvedDest(prependTo: Boolean): BilingualText {
    return lrtCircular?: dest.let { if (prependTo) it.prependTo() else it }
}

infix fun String.withEn(en: String): BilingualText {
    return BilingualText(this, en)
}

infix fun String.withZh(zh: String): BilingualText {
    return BilingualText(zh, this)
}

fun BilingualText.prependTo(): BilingualText {
    return bilingualToPrefix + this
}

operator fun BilingualText.component1(): String {
    return this.zh
}

operator fun BilingualText.component2(): String {
    return this.en
}

operator fun BilingualText.plus(other: BilingualText): BilingualText {
    return BilingualText(this.zh + other.zh, this.en + other.en)
}

operator fun BilingualText.plus(other: String): BilingualText {
    return BilingualText(this.zh + other, this.en + other)
}

fun DoubleArray.toCoordinates(): Coordinates {
    return Coordinates.fromArray(this)
}

fun Route.getRouteKey(context: Context): String? {
    return Registry.getInstance(context).getRouteKey(this)
}

fun String.asStop(context: Context): Stop? {
    return Registry.getInstance(context).getStopById(this)
}

fun String.identifyStopCo(): Operator? {
    return Operator.values().firstOrNull { it.matchStopIdPattern(this) }
}

inline val Stop.remarkedName: BilingualText get() {
    return if (this.remark == null) this.name else (this.name + "<small> " + this.remark + "</small>")
}

inline val RouteSearchResultEntry.uniqueKey: String get() {
    return if (stopInfo == null) routeKey else routeKey.plus(":").plus(stopInfo.stopId)
}

inline val CharSequence.operator: Operator get() = Operator.valueOf(toString())

inline val CharSequence.gmbRegion: GMBRegion? get() = GMBRegion.valueOfOrNull(toString().uppercase())

operator fun ETAQueryResult.get(index: Int): ETALineEntry {
    return this.getLine(index)
}

inline val ETAQueryResult.firstLine: ETALineEntry get() = this[1]

operator fun ETAShortText.component1(): String {
    return this.first
}

operator fun ETAShortText.component2(): String {
    return this.second
}

data class FavouriteResolvedStop(val index: Int, val stopId: String, val stop: Stop, val route: Route)

inline fun FavouriteRouteStop.resolveStop(context: Context, originGetter: () -> Coordinates?): FavouriteResolvedStop {
    if (favouriteStopMode == FavouriteStopMode.FIXED) {
        return FavouriteResolvedStop(index, stopId, stop, route)
    }
    val origin = originGetter.invoke()?: return FavouriteResolvedStop(index, stopId, stop, route)
    return Registry.getInstance(context).getAllStops(route.routeNumber, route.bound[co], co, route.gmbRegion)
        .withIndex()
        .minBy { it.value.stop.location.distance(origin) }
        .let { FavouriteResolvedStop(it.index + 1, it.value.stopId, it.value.stop, it.value.route) }
}

inline fun List<FavouriteRouteStop>.resolveStops(context: Context, originGetter: () -> Coordinates?): List<Pair<FavouriteRouteStop, FavouriteResolvedStop?>> {
    if (isEmpty()) {
        return emptyList()
    }
    if (any { it.favouriteStopMode == FavouriteStopMode.FIXED }) {
        return map { it to FavouriteResolvedStop(it.index, it.stopId, it.stop, it.route) }
    }
    val origin = originGetter.invoke()?: this[0].stop.location
    val eachAllStop = map { Registry.getInstanceNoUpdateCheck(context).getAllStops(it.route.routeNumber, it.route.bound[it.co], it.co, it.route.gmbRegion) }
    val closestStop = eachAllStop.flatten().minBy { it.stop.location.distance(origin) }
    return eachAllStop.withIndex().map { indexed ->
        val (index, allStops) = indexed
        allStops.withIndex()
            .map { it.value.stop.location.distance(closestStop.stop.location) to it }
            .minBy { it.first }
            .let { this[index] to (if (it.first <= 0.15) FavouriteResolvedStop(it.second.index + 1, it.second.value.stopId, it.second.value.stop, it.second.value.route) else null) }
    }
}