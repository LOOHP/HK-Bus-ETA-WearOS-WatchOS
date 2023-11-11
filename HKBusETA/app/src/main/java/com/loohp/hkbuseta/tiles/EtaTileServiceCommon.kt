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

package com.loohp.hkbuseta.tiles

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.ColorProp
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.ArcLine
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.expression.AnimationParameterBuilders
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicColor
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.aghajari.compose.text.asAnnotatedString
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.loohp.hkbuseta.MainActivity
import com.loohp.hkbuseta.objects.BilingualText
import com.loohp.hkbuseta.objects.FavouriteRouteStop
import com.loohp.hkbuseta.objects.Operator
import com.loohp.hkbuseta.objects.getColor
import com.loohp.hkbuseta.objects.getDisplayRouteNumber
import com.loohp.hkbuseta.objects.isTrain
import com.loohp.hkbuseta.objects.name
import com.loohp.hkbuseta.shared.Registry
import com.loohp.hkbuseta.shared.Registry.ETALineEntry
import com.loohp.hkbuseta.shared.Registry.ETAQueryResult
import com.loohp.hkbuseta.shared.Shared
import com.loohp.hkbuseta.utils.ScreenSizeUtils
import com.loohp.hkbuseta.utils.StringUtils
import com.loohp.hkbuseta.utils.UnitUtils
import com.loohp.hkbuseta.utils.addContentAnnotatedString
import com.loohp.hkbuseta.utils.adjustBrightness
import com.loohp.hkbuseta.utils.clampSp
import com.loohp.hkbuseta.utils.getAndNegate
import com.loohp.hkbuseta.utils.parallelMap
import com.loohp.hkbuseta.utils.parallelMapNotNull
import com.loohp.hkbuseta.utils.toSpanned
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Date
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Collectors
import kotlin.math.roundToInt


data class InlineImageResource(val data: ByteArray, val width: Int, val height: Int) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InlineImageResource

        if (!data.contentEquals(other.data)) return false
        if (width != other.width) return false
        if (height != other.height) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

@Immutable
class MergedETAQueryResult<T> private constructor(
    val isConnectionError: Boolean,
    val isMtrEndOfLine: Boolean,
    val isTyphoonSchedule: Boolean,
    val nextCo: Operator,
    private val lines: Map<Int, Pair<T, ETALineEntry>>,
    val mergedCount: Int
) {

    val nextScheduledBus: Long = lines[1]?.second?.eta?: -1
    val firstKey: T? = lines.minByOrNull { it.key }?.value?.first
    val allKeys: Set<T> = lines.entries.stream().map { it.value.first }.collect(Collectors.toSet())

    fun getLine(index: Int): Pair<T?, ETALineEntry> {
        return lines[index]?: (null to ETALineEntry.EMPTY)
    }

    companion object {

        fun <T> merge(etaQueryResult: List<Pair<T, ETAQueryResult>>): MergedETAQueryResult<T> {
            if (etaQueryResult.size == 1) {
                val (key, value) = etaQueryResult[0]
                val lines: MutableMap<Int, Pair<T, ETALineEntry>> = HashMap()
                value.rawLines.entries.forEach { lines[it.key] = key to it.value }
                return MergedETAQueryResult(value.isConnectionError, value.isMtrEndOfLine, value.isTyphoonSchedule, value.nextCo, lines, 1)
            }
            val isConnectionError = etaQueryResult.all { it.second.isConnectionError }
            val isMtrEndOfLine = etaQueryResult.all { it.second.isMtrEndOfLine }
            val isTyphoonSchedule = etaQueryResult.any { it.second.isTyphoonSchedule }
            val linesSorted: MutableList<Triple<T, ETALineEntry, Operator>> = etaQueryResult.toList().stream()
                .flatMap { it.second.rawLines.values.stream().map { line -> Triple(it.first, line, it.second.nextCo) } }
                .sorted(Comparator
                    .comparing<Triple<T, ETALineEntry, Operator>?, Long?> { it.second.eta.let { v -> if (v < 0) Long.MAX_VALUE else v } }
                    .thenComparing(Comparator.comparing { etaQueryResult.indexOfFirst { i -> i.first == it.first } })
                )
                .collect(Collectors.toCollection { ArrayList() })
            val nextCo = if (linesSorted.isEmpty()) etaQueryResult[0].second.nextCo else linesSorted[0].third
            val lines: MutableMap<Int, Pair<T, ETALineEntry>> = HashMap()
            if (linesSorted.any { it.second.eta >= 0 }) {
                linesSorted.removeIf { it.second.eta < 0 }
            }
            linesSorted.withIndex().forEach { lines[it.index + 1] = it.value.first to it.value.second }
            return MergedETAQueryResult(isConnectionError, isMtrEndOfLine, isTyphoonSchedule, nextCo, lines, etaQueryResult.size)
        }

    }

}

class TileState {

    private val updateTask: AtomicReference<ScheduledFuture<*>?> = AtomicReference(null)
    private val lastUpdated: AtomicLong = AtomicLong(0)
    private val cachedETAQueryResult: AtomicReference<Pair<MergedETAQueryResult<FavouriteRouteStop>?, Long>> = AtomicReference(null to 0)
    private val tileLayoutState: AtomicBoolean = AtomicBoolean(false)
    private val lastTileArcColor: AtomicInteger = AtomicInteger(Int.MIN_VALUE)
    private val lastUpdateSuccessful: AtomicBoolean = AtomicBoolean(false)
    private val isCurrentlyUpdating: AtomicLong = AtomicLong(0)

    fun setUpdateTask(future: ScheduledFuture<*>) {
        updateTask.updateAndGet { it?.cancel(false); future }
    }

    fun cancelUpdateTask() {
        updateTask.updateAndGet { it?.cancel(false); null }
    }

    fun markLastUpdated() {
        lastUpdated.set(System.currentTimeMillis())
    }

    fun markShouldUpdate() {
        lastUpdated.set(0)
    }

    fun shouldUpdate(): Boolean {
        return !isCurrentlyUpdating() && (System.currentTimeMillis() - lastUpdated.get() > Shared.ETA_UPDATE_INTERVAL)
    }

    fun cacheETAQueryResult(eta: MergedETAQueryResult<FavouriteRouteStop>?) {
        cachedETAQueryResult.set(eta to System.currentTimeMillis())
    }

    fun getETAQueryResult(orElse: (TileState) -> MergedETAQueryResult<FavouriteRouteStop>?): MergedETAQueryResult<FavouriteRouteStop>? {
        val (cache, time) = cachedETAQueryResult.getAndUpdate { null to 0 }
        return if (cache == null || System.currentTimeMillis() - time > 10000) orElse.invoke(this) else cache
    }

    fun getCurrentTileLayoutState(): Boolean {
        return tileLayoutState.getAndNegate()
    }

    fun getLastUpdateSuccessful(): Boolean {
        return lastUpdateSuccessful.get()
    }

    fun setLastUpdateSuccessful(value: Boolean) {
        lastUpdateSuccessful.set(value)
    }

    fun isCurrentlyUpdating(): Boolean {
        return System.currentTimeMillis() - isCurrentlyUpdating.get() < Shared.ETA_UPDATE_INTERVAL * 2
    }

    fun setCurrentlyUpdating(value: Boolean) {
        return isCurrentlyUpdating.set(if (value) System.currentTimeMillis() else 0)
    }

    fun getAndSetLastTileArcColor(value: Color): Color? {
        val lastValue = lastTileArcColor.getAndSet(value.toArgb())
        return if (lastValue == Int.MIN_VALUE) null else Color(lastValue)
    }
}

class EtaTileServiceCommon {

    companion object {

        private val resourceVersion: AtomicReference<String> = AtomicReference(UUID.randomUUID().toString())
        private val inlineImageResources: MutableMap<String, InlineImageResource> = ConcurrentHashMap()

        private val executor = Executors.newScheduledThreadPool(16)
        private val internalTileStates: MutableMap<Int, TileState> = ConcurrentHashMap()

        private fun tileState(etaIndex: Int): TileState {
            return internalTileStates.computeIfAbsent(etaIndex) { TileState() }
        }

        private fun addInlineImageResource(resource: InlineImageResource): String {
            val md = MessageDigest.getInstance("MD5")
            val hash = BigInteger(1, md.digest(resource.data)).toString(16).padStart(32, '0')
                .plus("_").plus(resource.width).plus("_").plus(resource.height)
            inlineImageResources.computeIfAbsent(hash) {
                resourceVersion.set(UUID.randomUUID().toString())
                resource
            }
            return hash
        }

        private fun targetWidth(context: Context, padding: Int): Int {
            return ScreenSizeUtils.getScreenWidth(context) - UnitUtils.dpToPixels(context, (padding * 2).toFloat()).roundToInt()
        }

        private fun noFavouriteRouteStop(tileId: Int, packageName: String, context: Context): LayoutElementBuilders.LayoutElement {
            val tileState = tileState(tileId)
            tileState.setLastUpdateSuccessful(true)
            tileState.getAndSetLastTileArcColor(Color.DarkGray)
            return if (tileId in (1 or Int.MIN_VALUE)..(8 or Int.MIN_VALUE)) {
                LayoutElementBuilders.Box.Builder()
                    .setWidth(expand())
                    .setHeight(expand())
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setClickable(
                                ModifiersBuilders.Clickable.Builder()
                                    .setId("open")
                                    .setOnClick(
                                        ActionBuilders.LaunchAction.Builder()
                                            .setAndroidActivity(
                                                ActionBuilders.AndroidActivity.Builder()
                                                    .setClassName(MainActivity::class.java.name)
                                                    .setPackageName(packageName)
                                                    .build()
                                            ).build()
                                    ).build()
                            ).build()
                    )
                    .addContent(
                        LayoutElementBuilders.Arc.Builder()
                            .setAnchorAngle(
                                DimensionBuilders.DegreesProp.Builder(0F).build()
                            )
                            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                            .addContent(
                                ArcLine.Builder()
                                    .setLength(
                                        DimensionBuilders.DegreesProp.Builder(360F).build()
                                    )
                                    .setThickness(
                                        DimensionBuilders.dp(7F)
                                    )
                                    .setColor(
                                        ColorProp.Builder(Color.DarkGray.toArgb()).build()
                                    ).build()
                            ).build()
                    )
                    .addContent(
                        LayoutElementBuilders.Row.Builder()
                            .setWidth(wrap())
                            .setHeight(wrap())
                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                            .addContent(
                                LayoutElementBuilders.Column.Builder()
                                    .setWidth(wrap())
                                    .setHeight(wrap())
                                    .setModifiers(
                                        ModifiersBuilders.Modifiers.Builder()
                                            .setPadding(
                                                ModifiersBuilders.Padding.Builder()
                                                    .setStart(DimensionBuilders.dp(20F))
                                                    .setEnd(DimensionBuilders.dp(20F))
                                                    .build()
                                            )
                                            .build()
                                    )
                                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                                    .addContent(
                                        LayoutElementBuilders.Text.Builder()
                                            .setMaxLines(Int.MAX_VALUE)
                                            .setFontStyle(
                                                FontStyle.Builder()
                                                    .setSize(
                                                        DimensionBuilders.sp(StringUtils.scaledSize(25F, context))
                                                    )
                                                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                                                    .setColor(
                                                        ColorProp.Builder(Color.Yellow.toArgb()).build()
                                                    ).build()
                                            )
                                            .setText((tileId and Int.MAX_VALUE).toString())
                                            .build()
                                    )
                                    .addContent(
                                        LayoutElementBuilders.Text.Builder()
                                            .setMaxLines(Int.MAX_VALUE)
                                            .setText(if (Shared.language == "en") {
                                                "\nNo selected route stop\nYou can set the route stop in the app."
                                            } else {
                                                "\n未有選擇路線巴士站\n你可在應用程式中選取"
                                            })
                                            .build()
                                    ).build()
                            ).build()
                    ).build()
            } else {
                LayoutElementBuilders.Box.Builder()
                    .setWidth(expand())
                    .setHeight(expand())
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setClickable(
                                ModifiersBuilders.Clickable.Builder()
                                    .setId("open")
                                    .setOnClick(
                                        ActionBuilders.LaunchAction.Builder()
                                            .setAndroidActivity(
                                                ActionBuilders.AndroidActivity.Builder()
                                                    .setClassName(MainActivity::class.java.name)
                                                    .setPackageName(packageName)
                                                    .build()
                                            ).build()
                                    ).build()
                            ).build()
                    )
                    .addContent(
                        LayoutElementBuilders.Arc.Builder()
                            .setAnchorAngle(
                                DimensionBuilders.DegreesProp.Builder(0F).build()
                            )
                            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                            .addContent(
                                ArcLine.Builder()
                                    .setLength(
                                        DimensionBuilders.DegreesProp.Builder(360F).build()
                                    )
                                    .setThickness(
                                        DimensionBuilders.dp(7F)
                                    )
                                    .setColor(
                                        ColorProp.Builder(Color.DarkGray.toArgb()).build()
                                    ).build()
                            ).build()
                    )
                    .addContent(
                        LayoutElementBuilders.Row.Builder()
                            .setWidth(wrap())
                            .setHeight(wrap())
                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                            .addContent(
                                LayoutElementBuilders.Column.Builder()
                                    .setWidth(wrap())
                                    .setHeight(wrap())
                                    .setModifiers(
                                        ModifiersBuilders.Modifiers.Builder()
                                            .setPadding(
                                                ModifiersBuilders.Padding.Builder()
                                                    .setStart(DimensionBuilders.dp(20F))
                                                    .setEnd(DimensionBuilders.dp(20F))
                                                    .build()
                                            )
                                            .build()
                                    )
                                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                                    .addContent(
                                        LayoutElementBuilders.Text.Builder()
                                            .setMaxLines(Int.MAX_VALUE)
                                            .setFontStyle(
                                                FontStyle.Builder()
                                                    .setSize(
                                                        DimensionBuilders.sp(StringUtils.scaledSize(15F, context))
                                                    ).build()
                                            )
                                            .setText(if (Shared.language == "en") {
                                                "Display selected favourite routes here\n"
                                            } else {
                                                "選擇最喜愛路線在此顯示\n"
                                            })
                                            .build()
                                    )
                                    .addContent(
                                        LayoutElementBuilders.Box.Builder()
                                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                                            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                                            .setModifiers(
                                                ModifiersBuilders.Modifiers.Builder()
                                                    .setBackground(
                                                        ModifiersBuilders.Background.Builder()
                                                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(StringUtils.scaledSize(17.5F, context))).build())
                                                            .setColor(ColorProp.Builder(Color(0xFF1A1A1A).toArgb()).build())
                                                            .build()
                                                    )
                                                    .setClickable(
                                                        ModifiersBuilders.Clickable.Builder()
                                                            .setOnClick(
                                                                ActionBuilders.LaunchAction.Builder()
                                                                    .setAndroidActivity(
                                                                        ActionBuilders.AndroidActivity.Builder()
                                                                            .setClassName(EtaTileConfigureActivity::class.java.name)
                                                                            .addKeyToExtraMapping("com.google.android.clockwork.EXTRA_PROVIDER_CONFIG_TILE_ID", ActionBuilders.intExtra(tileId))
                                                                            .setPackageName(packageName)
                                                                            .build()
                                                                    ).build()
                                                            ).build()
                                                    )
                                                    .build()
                                            )
                                            .setWidth(DimensionBuilders.dp(StringUtils.scaledSize(135F, context)))
                                            .setHeight(DimensionBuilders.dp(StringUtils.scaledSize(35F, context)))
                                            .addContent(
                                                LayoutElementBuilders.Text.Builder()
                                                    .setMaxLines(1)
                                                    .setText(if (Shared.language == "en") {
                                                        "Select Route"
                                                    } else {
                                                        "選取路線"
                                                    })
                                                    .setFontStyle(
                                                        FontStyle.Builder()
                                                            .setSize(
                                                                DimensionBuilders.sp(StringUtils.scaledSize(17F, context))
                                                            )
                                                            .setColor(
                                                                ColorProp.Builder(Color.Yellow.toArgb()).build()
                                                            ).build()
                                                    )
                                                    .build()
                                            )
                                            .build()
                                    )
                                    .build()
                            ).build()
                    ).build()
            }
        }

        private fun title(index: Int, stopName: BilingualText, routeNumber: String, co: Operator, context: Context): LayoutElementBuilders.Text {
            val name = stopName[Shared.language]
            val text = if (co.isTrain) name else index.toString().plus(". ").plus(name)
            return LayoutElementBuilders.Text.Builder()
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setPadding(
                            ModifiersBuilders.Padding.Builder()
                                .setStart(DimensionBuilders.dp(35F))
                                .setEnd(DimensionBuilders.dp(35F))
                                .build()
                        )
                        .build()
                )
                .setText(text)
                .setMaxLines(2)
                .setFontStyle(
                    FontStyle.Builder()
                        .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                        .setSize(
                            DimensionBuilders.sp(clampSp(context, StringUtils.findOptimalSp(context, text, targetWidth(context, 35), 2, 1F, 17F), dpMax = 17F))
                        )
                        .setColor(
                            ColorProp.Builder(Color.White.toArgb()).build()
                        )
                        .build()
                ).build()
        }

        private fun subTitle(destName: BilingualText, routeNumber: String, co: Operator, context: Context): LayoutElementBuilders.Text {
            val name = co.getDisplayRouteNumber(routeNumber).plus(" ").plus(destName[Shared.language])
            return LayoutElementBuilders.Text.Builder()
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setPadding(
                            ModifiersBuilders.Padding.Builder()
                                .setStart(DimensionBuilders.dp(20F))
                                .setEnd(DimensionBuilders.dp(20F))
                                .build()
                        )
                        .build()
                )
                .setText(name)
                .setMaxLines(1)
                .setFontStyle(
                    FontStyle.Builder()
                        .setSize(
                            DimensionBuilders.sp(clampSp(context, StringUtils.findOptimalSp(context, name, targetWidth(context, 35), 1, 1F, 11F), dpMax = 11F))
                        )
                        .setColor(
                            ColorProp.Builder(Color.White.toArgb()).build()
                        )
                        .build()
                ).build()
        }

        @androidx.annotation.OptIn(androidx.wear.protolayout.expression.ProtoLayoutExperimental::class)
        private fun etaText(eta: MergedETAQueryResult<FavouriteRouteStop>?, seq: Int, mainFavouriteStopRoute: FavouriteRouteStop, packageName: String, context: Context): LayoutElementBuilders.Spannable {
            val line = eta?.getLine(seq)
            val lineRoute = line?.first?.let { it.co.getDisplayRouteNumber(it.route.routeNumber, true) }
            val appendRouteNumber = lineRoute == null ||
                    (1..3).all { eta.getLine(it).first?.route?.routeNumber.let { route -> route == null || route == line.first?.route?.routeNumber } } ||
                    eta.allKeys.all { it.co == Operator.MTR } ||
                    eta.mergedCount <= 1
            val raw = (if (appendRouteNumber) "" else "<small>$lineRoute > </small>")
                .plus(line?.second?.text?: if (seq == 1) (if (Shared.language == "en") "Updating" else "更新中") else "")
            val measure = raw.toSpanned(context, 17F).asAnnotatedString().annotatedString.text
            val color = Color.White.toArgb()
            val maxTextSize = if (seq == 1) 15F else if (Shared.language == "en") 11F else 13F
            val textSize = clampSp(context, StringUtils.findOptimalSp(context, measure, targetWidth(context, 20) / 10 * 8, 1, maxTextSize - 2F, maxTextSize), dpMax = maxTextSize)
            val text = raw.toSpanned(context, textSize).asAnnotatedString()

            val favouriteStopRoute = line?.first?: mainFavouriteStopRoute

            val stopId = favouriteStopRoute.stopId
            val co = favouriteStopRoute.co
            val index = favouriteStopRoute.index
            val stop = favouriteStopRoute.stop
            val route = favouriteStopRoute.route

            return LayoutElementBuilders.Spannable.Builder()
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setPadding(
                            ModifiersBuilders.Padding.Builder()
                                .setStart(DimensionBuilders.dp(if (seq == 1) 20F else 35F))
                                .setEnd(DimensionBuilders.dp(if (seq == 1) 20F else 35F))
                                .build()
                        )
                        .setClickable(
                            ModifiersBuilders.Clickable.Builder()
                                .setId("open")
                                .setOnClick(
                                    ActionBuilders.LaunchAction.Builder()
                                        .setAndroidActivity(
                                            ActionBuilders.AndroidActivity.Builder()
                                                .setClassName(MainActivity::class.java.name)
                                                .addKeyToExtraMapping("stopId", ActionBuilders.stringExtra(stopId))
                                                .addKeyToExtraMapping("co", ActionBuilders.stringExtra(co.name))
                                                .addKeyToExtraMapping("index", ActionBuilders.intExtra(index))
                                                .addKeyToExtraMapping("stop", ActionBuilders.stringExtra(stop.serialize().toString()))
                                                .addKeyToExtraMapping("route", ActionBuilders.stringExtra(route.serialize().toString()))
                                                .setPackageName(packageName)
                                                .build()
                                        ).build()
                                ).build()
                        )
                        .build()
                )
                .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_MARQUEE)
                .setMaxLines(1)
                .addContentAnnotatedString(context, text, textSize, {
                    it.setColor(
                        ColorProp.Builder(color).build()
                    )
                }, { d, w, h -> addInlineImageResource(InlineImageResource(d, w, h)) })
                .build()
        }

        private fun lastUpdated(context: Context): LayoutElementBuilders.Text {
            return LayoutElementBuilders.Text.Builder()
                .setMaxLines(1)
                .setText((if (Shared.language == "en") "Updated: " else "更新時間: ").plus(DateFormat.getTimeFormat(context).format(Date())))
                .setFontStyle(
                    FontStyle.Builder()
                        .setSize(
                            DimensionBuilders.sp(clampSp(context, StringUtils.scaledSize(9F, context), dpMax = 9F))
                        ).build()
                ).build()
        }

        private fun buildLayout(tileId: Int, favouriteStopRoutes: List<FavouriteRouteStop>, packageName: String, context: Context): LayoutElementBuilders.LayoutElement {
            val tileState = tileState(tileId)

            val eta = tileState.getETAQueryResult {
                val eta = MergedETAQueryResult.merge(
                    favouriteStopRoutes.parallelMap(executor) { stop ->
                        stop to Registry.getInstanceNoUpdateCheck(context).getEta(stop.stopId, stop.co, stop.route, context).get(7000, TimeUnit.MILLISECONDS)
                    }
                )
                it.markLastUpdated()
                eta
            }
            val favouriteStopRoute = eta?.firstKey?: favouriteStopRoutes[0]

            val stopId = favouriteStopRoute.stopId
            val co = favouriteStopRoute.co
            val index = favouriteStopRoute.index
            val stop = favouriteStopRoute.stop
            val route = favouriteStopRoute.route

            val routeNumber = route.routeNumber
            val stopName = stop.name
            val destName = Registry.getInstanceNoUpdateCheck(context).getStopSpecialDestinations(stopId, co, route, true)

            val color = if (eta == null || eta.isConnectionError) {
                tileState.setLastUpdateSuccessful(false)
                Color.DarkGray
            } else {
                tileState.setLastUpdateSuccessful(true)
                eta.nextCo.getColor(routeNumber, Color.LightGray).adjustBrightness(if (eta.nextScheduledBus < 0 || eta.nextScheduledBus > 60) 0.2F else 1F)
            }
            val previousColor = tileState.getAndSetLastTileArcColor(color)?: color
            val colorProp = ColorProp.Builder(color.toArgb())
            if (previousColor != color) {
                colorProp.setDynamicValue(
                    DynamicColor.animate(previousColor.toArgb(), color.toArgb(),
                        AnimationParameterBuilders.AnimationSpec.Builder()
                            .setAnimationParameters(
                                AnimationParameterBuilders.AnimationParameters.Builder()
                                    .setDurationMillis(1000)
                                    .build()
                            ).build()
                    )
                )
            }

            return LayoutElementBuilders.Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setClickable(
                            ModifiersBuilders.Clickable.Builder()
                                .setId("open")
                                .setOnClick(
                                    ActionBuilders.LaunchAction.Builder()
                                        .setAndroidActivity(
                                            ActionBuilders.AndroidActivity.Builder()
                                                .setClassName(MainActivity::class.java.name)
                                                .addKeyToExtraMapping("stopId", ActionBuilders.stringExtra(stopId))
                                                .addKeyToExtraMapping("co", ActionBuilders.stringExtra(co.name))
                                                .addKeyToExtraMapping("index", ActionBuilders.intExtra(index))
                                                .addKeyToExtraMapping("stop", ActionBuilders.stringExtra(stop.serialize().toString()))
                                                .addKeyToExtraMapping("route", ActionBuilders.stringExtra(route.serialize().toString()))
                                                .setPackageName(packageName)
                                                .build()
                                        ).build()
                                ).build()
                        ).build()
                )
                .addContent(
                    LayoutElementBuilders.Arc.Builder()
                        .setAnchorAngle(
                            DimensionBuilders.DegreesProp.Builder(0F).build()
                        )
                        .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                        .addContent(
                            ArcLine.Builder()
                                .setLength(
                                    DimensionBuilders.DegreesProp.Builder(360F).build()
                                )
                                .setThickness(
                                    DimensionBuilders.dp(7F)
                                )
                                .setColor(
                                    colorProp.build()
                                ).build()
                        ).build()
                )
                .addContent(
                    LayoutElementBuilders.Row.Builder()
                        .setWidth(wrap())
                        .setHeight(wrap())
                        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                        .addContent(
                            LayoutElementBuilders.Column.Builder()
                                .setWidth(wrap())
                                .setHeight(wrap())
                                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                                .addContent(
                                    LayoutElementBuilders.Spacer.Builder()
                                        .setHeight(
                                            DimensionBuilders.dp(StringUtils.scaledSize(16F, context))
                                        ).build()
                                ).addContent(
                                    title(index, stopName, routeNumber, co, context)
                                )
                                .addContent(
                                    subTitle(destName, routeNumber, co, context)
                                )
                                .addContent(
                                    LayoutElementBuilders.Spacer.Builder()
                                        .setHeight(
                                            DimensionBuilders.dp(StringUtils.scaledSize(12F, context))
                                        ).build()
                                ).addContent(
                                    etaText(eta, 1, favouriteStopRoute, packageName, context)
                                ).addContent(
                                    LayoutElementBuilders.Spacer.Builder()
                                        .setHeight(
                                            DimensionBuilders.dp(StringUtils.scaledSize(7F, context))
                                        ).build()
                                ).addContent(
                                    etaText(eta, 2, favouriteStopRoute, packageName, context)
                                ).addContent(
                                    LayoutElementBuilders.Spacer.Builder()
                                        .setHeight(
                                            DimensionBuilders.dp(StringUtils.scaledSize(7F, context))
                                        ).build()
                                ).addContent(
                                    etaText(eta, 3, favouriteStopRoute, packageName, context)
                                ).addContent(
                                    LayoutElementBuilders.Spacer.Builder()
                                        .setHeight(
                                            DimensionBuilders.dp(StringUtils.scaledSize(7F, context))
                                        ).build()
                                ).addContent(
                                    lastUpdated(context)
                                ).build()
                        ).build()
                ).build()
        }

        private fun buildSuitableElement(tileId: Int, packageName: String, context: Context): LayoutElementBuilders.LayoutElement {
            while (Registry.getInstanceNoUpdateCheck(context).state.value.isProcessing) {
                TimeUnit.MILLISECONDS.sleep(10)
            }
            val favouriteRoutes = Shared.getEtaTileConfiguration(tileId)
            return if (favouriteRoutes.isEmpty() || favouriteRoutes.none { Shared.favoriteRouteStops[it] != null }) {
                noFavouriteRouteStop(tileId, packageName, context)
            } else {
                buildLayout(tileId, favouriteRoutes.mapNotNull { Shared.favoriteRouteStops[it] }, packageName, context)
            }
        }

        fun buildTileRequest(tileId: Int, packageName: String, context: TileService): ListenableFuture<TileBuilders.Tile> {
            val tileState = tileState(tileId)
            tileState.setCurrentlyUpdating(true)
            return Futures.submit(Callable {
                try {
                    val element = buildSuitableElement(tileId, packageName, context)
                    val stateElement = if (tileState.getCurrentTileLayoutState()) {
                        element
                    } else {
                        LayoutElementBuilders.Box.Builder()
                            .setWidth(expand())
                            .setHeight(expand())
                            .addContent(element)
                            .build()
                    }
                    TileBuilders.Tile.Builder()
                        .setResourcesVersion(resourceVersion.get().toString())
                        .setFreshnessIntervalMillis(0)
                        .setTileTimeline(
                            TimelineBuilders.Timeline.Builder().addTimelineEntry(
                                TimelineBuilders.TimelineEntry.Builder().setLayout(
                                    LayoutElementBuilders.Layout.Builder().setRoot(
                                        stateElement
                                    ).build()
                                ).build()
                            ).build()
                        ).build()
                } finally {
                    tileState.setCurrentlyUpdating(false)
                }
            }, executor)
        }

        fun buildTileResourcesRequest(): ListenableFuture<ResourceBuilders.Resources> {
            return Futures.submit(Callable {
                val resourceBuilder = ResourceBuilders.Resources.Builder().setVersion(resourceVersion.get().toString())
                for ((key, resource) in inlineImageResources) {
                    resourceBuilder.addIdToImageMapping(key, ResourceBuilders.ImageResource.Builder()
                        .setInlineResource(
                            ResourceBuilders.InlineImageResource.Builder()
                                .setData(resource.data)
                                .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                                .setWidthPx(resource.width)
                                .setHeightPx(resource.height)
                                .build()
                        )
                        .build()
                    )
                }
                resourceBuilder.build()
            }, executor)
        }

        fun handleTileEnterEvent(tileId: Int, context: TileService) {
            tileState(tileId).let {
                if (!it.getLastUpdateSuccessful()) {
                    it.markShouldUpdate()
                }
                it.setUpdateTask(executor.scheduleWithFixedDelay({
                    while (Registry.getInstanceNoUpdateCheck(context).state.value.isProcessing) {
                        TimeUnit.MILLISECONDS.sleep(10)
                    }
                    if (it.shouldUpdate()) {
                        val favouriteRoutes = Shared.getEtaTileConfiguration(tileId)
                        if (favouriteRoutes.isNotEmpty()) {
                            it.cacheETAQueryResult(MergedETAQueryResult.merge(
                                favouriteRoutes.parallelMapNotNull(executor) { favouriteRoute ->
                                    Shared.favoriteRouteStops[favouriteRoute]?.let { stop ->
                                        stop to Registry.getInstanceNoUpdateCheck(context).getEta(stop.stopId, stop.co, stop.route, context).get(Shared.ETA_UPDATE_INTERVAL, TimeUnit.MILLISECONDS)
                                    }
                                }
                            ))
                            it.markLastUpdated()
                            TileService.getUpdater(context).requestUpdate(context.javaClass)
                        }
                    }
                }, 0, 1000, TimeUnit.MILLISECONDS))
            }
        }

        fun handleTileLeaveEvent(tileId: Int) {
            tileState(tileId).cancelUpdateTask()
        }

        fun handleTileRemoveEvent(tileId: Int, context: Context) {
            handleTileLeaveEvent(tileId)
            Registry.getInstance(context).clearEtaTileConfiguration(tileId, context)
        }

    }

}