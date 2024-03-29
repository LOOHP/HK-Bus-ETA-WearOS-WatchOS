//
//  EtaView.swift
//  hkbuseta Watch App
//
//  Created by LOOHP on 22/12/2023.
//

import SwiftUI
import shared
import KMPNativeCoroutinesCore
import KMPNativeCoroutinesRxSwift
import KMPNativeCoroutinesAsync
import KMPNativeCoroutinesCombine
import RxSwift

struct EtaMenuView: AppScreenView {
    
    @StateObject private var jointOperatedColorFraction = FlowStateObservable(defaultValue: KotlinFloat(float: Shared().jointOperatedColorFractionState), nativeFlow: Shared().jointOperatedColorFractionStateFlow)
    
    @StateObject private var maxFavItems = FlowStateObservable(defaultValue: KotlinInt(int: Shared().suggestedMaxFavouriteRouteStopState), nativeFlow: Shared().suggestedMaxFavouriteRouteStopStateFlow)
    
    @State private var stopId: String
    @State private var co: Operator
    @State private var index: Int
    @State private var stop: Stop
    @State private var route: Route
    @State private var offsetStart: Int
    
    @State private var stopList: [Registry.StopData]
    @State private var favStates: [Int: FavouriteRouteState] = [:]
    
    private let appContext: AppActiveContextWatchOS
    
    init(appContext: AppActiveContextWatchOS, data: [String: Any], storage: KotlinMutableDictionary<NSString, AnyObject>) {
        self.appContext = appContext
        self.stopId = data["stopId"] as! String
        let co = data["co"] as! Operator
        self.co = co
        self.index = data["index"] as! Int
        self.stop = data["stop"] as! Stop
        let route = data["route"] as! Route
        self.route = route
        self.offsetStart = data["offsetStart"] as? Int ?? 0
        
        self.stopList = registry(appContext).getAllStops(routeNumber: route.routeNumber, bound: co == Operator.Companion().NLB ? route.nlbId : route.bound[co]!, co: co, gmbRegion: route.gmbRegion)
    }
    
    var body: some View {
        ScrollViewReader { value in
            ScrollView(.vertical) {
                VStack(alignment: .center, spacing: 1.scaled(appContext)) {
                    Spacer().frame(fixedSize: 10.scaled(appContext))
                    VStack(alignment: .center) {
                        Text(co.isTrain ? stop.name.get(language: Shared().language) : "\(index). \(stop.name.get(language: Shared().language))")
                            .multilineTextAlignment(.center)
                            .foregroundColor(colorInt(0xFFFFFFFF).asColor())
                            .lineLimit(2)
                            .autoResizing(maxSize: 23.scaled(appContext, true), weight: .bold)
                        if (stop.remark != nil) {
                            Text(stop.remark!.get(language: Shared().language))
                                .foregroundColor(colorInt(0xFFFFFFFF).asColor())
                                .lineLimit(1)
                                .autoResizing(maxSize: 13.scaled(appContext, true))
                        }
                        let destName = registry(appContext).getStopSpecialDestinations(stopId: stopId, co: co, route: route, prependTo: true)
                        Text(co.getDisplayRouteNumber(routeNumber: route.routeNumber, shortened: false) + " " + destName.get(language: Shared().language))
                            .foregroundColor(colorInt(0xFFFFFFFF).asColor())
                            .lineLimit(1)
                            .autoResizing(maxSize: 12.scaled(appContext, true))
                    }
                    Spacer().frame(fixedSize: 10.scaled(appContext))
                    Text(Shared().language == "en" ? "More Info & Actions" : "更多資訊及功能")
                        .foregroundColor(colorInt(0xFFFFFFFF).asColor())
                        .lineLimit(1)
                        .autoResizing(maxSize: 14.scaled(appContext, true))
                    Spacer().frame(fixedSize: 5.scaled(appContext))
                    if stop.kmbBbiId != nil {
                        KmbBbiButton(kmbBbiId: stop.kmbBbiId!)
                    }
                    Spacer().frame(fixedSize: 5.scaled(appContext))
                    SearchNearbyButton()
                    Spacer().frame(fixedSize: 5.scaled(appContext))
                    OpenOnMapsButton(stopName: stop.name, lat: stop.location.lat, lng: stop.location.lng)
                    Spacer().frame(fixedSize: 10.scaled(appContext))
                    Text(Shared().language == "en" ? "Set Favourite Routes" : "設置最喜愛路線")
                        .font(.system(size: 14.scaled(appContext, true)))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 20.scaled(appContext))
                    Text(Shared().language == "en" ? "Section to set/clear this route stop from the corresponding indexed favourite route" : "以下可設置/清除對應的最喜愛路線")
                        .font(.system(size: 10.scaled(appContext, true)))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 20.scaled(appContext))
                    Text(Shared().language == "en" ? "Route stops can be used in Tiles" : "最喜愛路線可在資訊方塊中顯示")
                        .font(.system(size: 10.scaled(appContext, true)))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 20.scaled(appContext))
                    Spacer().frame(height: 5.scaled(appContext))
                    Text(Shared().language == "en" ? "Tap to set this stop\nLong press to set to display any closes stop of the route" : "點擊設置此站 長按設置顯示路線最近的任何站")
                        .font(.system(size: 10.scaled(appContext, true), weight: .bold))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 20.scaled(appContext))
                    Spacer().frame(fixedSize: 10.scaled(appContext))
                    Button(action: {
                        let target = (1...30).first {
                            let favState = getFavState(favoriteIndex: $0, stopId: stopId, co: co, index: index, stop: stop, route: route)
                            return favState == .notUsed || favState == .usedSelf
                        } ?? 30
                        value.scrollTo(target, anchor: .center)
                    }) {
                        Image(systemName: "chevron.down")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 15.scaled(appContext), height: 15.scaled(appContext))
                            .padding(3.scaled(appContext))
                            .foregroundColor(colorInt(0xFFFFB700).asColor())
                    }
                    .frame(width: 50.scaled(appContext), height: 30.scaled(appContext))
                    .clipShape(RoundedRectangle(cornerRadius: 25))
                    Spacer().frame(fixedSize: 10.scaled(appContext))
                    ForEach(1..<(Int(truncating: maxFavItems.state) + 1), id: \.self) { index in
                        FavButton(favIndex: index).id(index)
                        Spacer().frame(fixedSize: 5.scaled(appContext))
                    }
                }
            }
        }
        .onAppear {
            maxFavItems.subscribe()
        }
        .onDisappear {
            jointOperatedColorFraction.unsubscribe()
            maxFavItems.unsubscribe()
        }
    }
    
    func OpenOnMapsButton(stopName: BilingualText, lat: Double, lng: Double) -> some View {
        Button(action: {}) {
            HStack(alignment: .center, spacing: 2.scaled(appContext)) {
                ZStack(alignment: .topLeading) {
                    Text("").frame(maxHeight: .infinity)
                    ZStack {
                        Circle()
                            .fill(Color(red: 61/255, green: 61/255, blue: 61/255))
                            .frame(width: 30.scaled(appContext), height: 30.scaled(appContext))
                        Image(systemName: "map")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 17.scaled(appContext, true), height: 17.scaled(appContext, true))
                            .foregroundColor(colorInt(0xFF4CFF00).asColor())
                    }
                }.frame(maxHeight: .infinity)
                Text(Shared().language == "en" ? "Open Stop Location on Maps" : "在地圖上顯示巴士站位置")
                    .font(.system(size: 14.5.scaled(appContext, true), weight: .bold))
                    .foregroundColor(colorInt(0xFFFFFFFF).asColor())
                    .lineLimit(2)
                    .lineSpacing(0)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(10.scaled(appContext))
        }.background(
            Image("open_map_background")
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: 178.0.scaled(appContext), height: 47.0.scaled(appContext, true))
                .brightness(-0.4)
                .clipShape(RoundedRectangle(cornerRadius: 23.5.scaled(appContext)))
        )
        .buttonStyle(PlainButtonStyle())
        .frame(width: 178.0.scaled(appContext), height: 47.0.scaled(appContext, true))
        .clipShape(RoundedRectangle(cornerRadius: 23.5.scaled(appContext)))
        .simultaneousGesture(
            LongPressGesture()
                .onEnded { _ in
                    appContext.handleOpenMaps(lat: lat, lng: lng, label: stopName.get(language: Shared().language), longClick: true, haptics: hapticsFeedback())()
                }
        )
        .highPriorityGesture(
            TapGesture()
                .onEnded { _ in
                    appContext.handleOpenMaps(lat: lat, lng: lng, label: stopName.get(language: Shared().language), longClick: true, haptics: hapticsFeedback())()
                }
        )
    }
    
    func KmbBbiButton(kmbBbiId: String) -> some View {
        Button(action: {}) {
            HStack(alignment: .center, spacing: 2.scaled(appContext)) {
                ZStack(alignment: .topLeading) {
                    Text("").frame(maxHeight: .infinity)
                    ZStack {
                        Circle()
                            .fill(Color(red: 61/255, green: 61/255, blue: 61/255))
                            .frame(width: 30.scaled(appContext), height: 30.scaled(appContext))
                        Image(systemName: "figure.walk")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 17.scaled(appContext, true), height: 17.scaled(appContext, true))
                            .foregroundColor(colorInt(0xFFFF0000).asColor())
                    }
                }.frame(maxHeight: .infinity)
                Text(Shared().language == "en" ? "Open KMB BBI Layout Map" : "顯示九巴轉車站位置圖")
                    .font(.system(size: 14.5.scaled(appContext, true), weight: .bold))
                    .foregroundColor(colorInt(0xFFFFFFFF).asColor())
                    .lineLimit(2)
                    .lineSpacing(0)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(10.scaled(appContext))
        }.background(
            Image("kmb_bbi_background")
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: 178.0.scaled(appContext), height: 47.0.scaled(appContext, true))
                .brightness(-0.4)
                .clipShape(RoundedRectangle(cornerRadius: 23.5.scaled(appContext)))
        )
        .buttonStyle(PlainButtonStyle())
        .frame(width: 178.0.scaled(appContext), height: 47.0.scaled(appContext, true))
        .clipShape(RoundedRectangle(cornerRadius: 23.5.scaled(appContext)))
        .simultaneousGesture(
            LongPressGesture()
                .onEnded { _ in
                    let url = "https://app.kmb.hk/app1933/BBI/map/\(kmbBbiId).jpg"
                    appContext.handleWebImages(url: url, longClick: true, haptics: hapticsFeedback())()
                }
        )
        .highPriorityGesture(
            TapGesture()
                .onEnded { _ in
                    let url = "https://app.kmb.hk/app1933/BBI/map/\(kmbBbiId).jpg"
                    appContext.handleWebImages(url: url, longClick: false, haptics: hapticsFeedback())()
                }
        )
    }
    
    func SearchNearbyButton() -> some View {
        Button(action: {}) {
            HStack(alignment: .center, spacing: 2.scaled(appContext)) {
                ZStack(alignment: .topLeading) {
                    Text("").frame(maxHeight: .infinity)
                    ZStack {
                        Circle()
                            .fill(Color(red: 61/255, green: 61/255, blue: 61/255))
                            .frame(width: 30.scaled(appContext), height: 30.scaled(appContext))
                        Image(systemName: "bus.doubledecker")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 17.scaled(appContext, true), height: 17.scaled(appContext, true))
                            .foregroundColor(colorInt(0xFFFFE15E).asColor())
                    }
                }.frame(maxHeight: .infinity)
                Text(Shared().language == "en" ? "Find Nearby Interchanges" : "尋找附近轉乘路線")
                    .font(.system(size: 14.5.scaled(appContext, true), weight: .bold))
                    .foregroundColor(colorInt(0xFFFFFFFF).asColor())
                    .lineLimit(2)
                    .lineSpacing(0)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(10.scaled(appContext))
        }.background(
            Image("interchange_background")
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: 178.0.scaled(appContext), height: 47.0.scaled(appContext, true))
                .brightness(-0.4)
                .clipShape(RoundedRectangle(cornerRadius: 23.5.scaled(appContext)))
        )
        .buttonStyle(PlainButtonStyle())
        .frame(width: 178.0.scaled(appContext), height: 47.0.scaled(appContext, true))
        .clipShape(RoundedRectangle(cornerRadius: 23.5.scaled(appContext)))
        .simultaneousGesture(
            LongPressGesture()
                .onEnded { _ in
                    playHaptics()
                    let data = newAppDataConatiner()
                    data["interchangeSearch"] = true
                    data["lat"] = stop.location.lat
                    data["lng"] = stop.location.lng
                    data["exclude"] = [route.routeNumber]
                    appContext.startActivity(appIntent: newAppIntent(appContext, AppScreen.nearby, data))
                }
        )
        .highPriorityGesture(
            TapGesture()
                .onEnded { _ in
                    let data = newAppDataConatiner()
                    data["interchangeSearch"] = true
                    data["lat"] = stop.location.lat
                    data["lng"] = stop.location.lng
                    data["exclude"] = [route.routeNumber]
                    appContext.startActivity(appIntent: newAppIntent(appContext, AppScreen.nearby, data))
                }
        )
    }
    
    func getFavState(favoriteIndex: Int, stopId: String, co: Operator, index: Int, stop: Stop, route: Route) -> FavouriteRouteState {
        if (registry(appContext).hasFavouriteRouteStop(favoriteIndex: favoriteIndex.asInt32())) {
            return registry(appContext).isFavouriteRouteStop(favoriteIndex: favoriteIndex.asInt32(), stopId: stopId, co: co, index: index.asInt32(), stop: stop, route: route) ? FavouriteRouteState.usedSelf : FavouriteRouteState.usedOther
        }
        return FavouriteRouteState.notUsed
    }
    
    func FavButton(favIndex: Int) -> some View {
        let state = favStates[favIndex] ?? {
            let s = getFavState(favoriteIndex: favIndex, stopId: stopId, co: co, index: index, stop: stop, route: route)
            DispatchQueue.main.async {
                favStates[favIndex] = s
            }
            return s
        }()
        let anyTileUses = Tiles().getTileUseState(index: favIndex.asInt32())
        let handleClick: (FavouriteStopMode) -> Void = {
            if state == FavouriteRouteState.usedSelf {
                registry(appContext).clearFavouriteRouteStop(favoriteIndex: favIndex.asInt32(), context: appContext)
                appContext.showToastText(text: Shared().language == "en" ? "Cleared Favourite Route \(favIndex)" : "已清除最喜愛路線\(favIndex)", duration: ToastDuration.short_)
            } else {
                registry(appContext).setFavouriteRouteStop(favoriteIndex: favIndex.asInt32(), stopId: stopId, co: co, index: index.asInt32(), stop: stop, route: route, favouriteStopMode: $0, context: appContext)
                appContext.showToastText(text: Shared().language == "en" ? "Set Favourite Route \(favIndex)" : "已設置最喜愛路線\(favIndex)", duration: ToastDuration.short_)
            }
            favStates[favIndex] = getFavState(favoriteIndex: favIndex, stopId: stopId, co: co, index: index, stop: stop, route: route)
        }
        return Button(action: {}) {
            HStack(alignment: .center, spacing: 2.scaled(appContext)) {
                ZStack(alignment: .topLeading) {
                    Text("").frame(maxHeight: .infinity)
                    ZStack {
                        Circle()
                            .fill(state == FavouriteRouteState.usedSelf ? colorInt(0xFF3D3D3D).asColor() : colorInt(0xFF131313).asColor())
                            .frame(width: 30.scaled(appContext), height: 30.scaled(appContext))
                        Text("\(favIndex)")
                            .font(.system(size: 17.scaled(appContext, true), weight: .bold))
                            .foregroundColor({
                                switch state {
                                case FavouriteRouteState.usedOther:
                                    return colorInt(0xFF4E4E00)
                                case FavouriteRouteState.usedSelf:
                                    return colorInt(0xFFFFFF00)
                                default:
                                    return colorInt(0xFF444444)
                                }
                            }().asColor())
                    }
                }.frame(maxHeight: .infinity)
                VStack(alignment: .leading, spacing: 0) {
                    switch state {
                    case FavouriteRouteState.notUsed:
                        Text(Shared().language == "en" ? "No Route Stop Selected" : "未有設置路線巴士站")
                            .font(.system(size: 15.scaled(appContext, true), weight: .bold))
                            .foregroundColor(colorInt(0xFFB9B9B9).asColor())
                            .lineLimit(2)
                            .lineSpacing(0)
                            .multilineTextAlignment(.leading)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)
                    case FavouriteRouteState.usedOther:
                        let currentRoute = Shared().favoriteRouteStops[KotlinInt(int: favIndex.asInt32())]!
                        let kmbCtbJoint = currentRoute.route.isKmbCtbJoint
                        let coDisplay = currentRoute.co.getDisplayName(routeNumber: currentRoute.route.routeNumber, kmbCtbJoint: kmbCtbJoint, language: Shared().language, elseName: "???")
                        let routeNumberDisplay = currentRoute.co.getDisplayRouteNumber(routeNumber: currentRoute.route.routeNumber, shortened: false)
                        let stopName = {
                            if (currentRoute.favouriteStopMode == FavouriteStopMode.fixed) {
                                if (Shared().language == "en") {
                                    return (currentRoute.co == Operator.Companion().MTR || currentRoute.co == Operator.Companion().LRT ? "" : "\(index). ") + currentRoute.stop.name.en
                                } else {
                                    return (currentRoute.co == Operator.Companion().MTR || currentRoute.co == Operator.Companion().LRT ? "" : "\(index). ") + currentRoute.stop.name.zh
                                }
                            } else {
                                return Shared().language == "en" ? "Any" : "任何站"
                            }
                        }()
                        let color = operatorColor(currentRoute.co.getColor(routeNumber: currentRoute.route.routeNumber, elseColor: 0xFFFFFFFF as Int64), Operator.Companion().CTB.getOperatorColor(elseColor: 0xFFFFFFFF as Int64), jointOperatedColorFraction.state.floatValue) { _ in kmbCtbJoint }.asColor()
                        MarqueeText(
                            text: "\(coDisplay) \(routeNumberDisplay)",
                            font: UIFont.systemFont(ofSize: 15.scaled(appContext, true), weight: .bold),
                            startDelay: 2,
                            alignment: .bottomLeading
                        )
                            .foregroundColor(color.adjustBrightness(percentage: 0.3))
                            .lineLimit(1)
                            .lineSpacing(0)
                            .multilineTextAlignment(.leading)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .onAppear {
                                if kmbCtbJoint {
                                    jointOperatedColorFraction.subscribe()
                                }
                            }
                        MarqueeText(
                            text: stopName,
                            font: UIFont.systemFont(ofSize: 15.scaled(appContext, true)),
                            startDelay: 2,
                            alignment: .bottomLeading
                        )
                            .foregroundColor(colorInt(0xFFFFFFFF).asColor().adjustBrightness(percentage: 0.3))
                            .lineLimit(1)
                            .lineSpacing(0)
                            .multilineTextAlignment(.leading)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    case FavouriteRouteState.usedSelf:
                        let isClosestStopMode = Shared().favoriteRouteStops[KotlinInt(int: favIndex.asInt32())]?.favouriteStopMode == FavouriteStopMode.closest
                        Text(isClosestStopMode ? (Shared().language == "en" ? "Selected as Any Closes Stop on This Route" : "已設置為本路線最近的任何巴士站") : (Shared().language == "en" ? "Selected as This Route Stop" : "已設置為本路線巴士站"))
                            .font(.system(size: 15.scaled(appContext, true), weight: .bold))
                            .foregroundColor((isClosestStopMode ? colorInt(0xFFFFE496) : colorInt(0xFFFFFFFF)).asColor())
                            .lineLimit(2)
                            .lineSpacing(0)
                            .multilineTextAlignment(.leading)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)
                    default:
                        Text("")
                    }
                }
            }
            .padding(10)
        }
        .buttonStyle(PlainButtonStyle())
        .background { colorInt(0xFF1A1A1A).asColor() }
        .frame(width: 178.0.scaled(appContext), height: 47.0.scaled(appContext, true))
        .clipShape(RoundedRectangle(cornerRadius: 23.5.scaled(appContext)))
        .tileStateBorder(anyTileUses, 23.5.scaled(appContext))
        .simultaneousGesture(
            LongPressGesture()
                .onEnded { _ in
                    playHaptics()
                    handleClick(FavouriteStopMode.closest)
                }
        )
        .highPriorityGesture(
            TapGesture()
                .onEnded { _ in
                    handleClick(FavouriteStopMode.fixed)
                }
        )
    }

}
