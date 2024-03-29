//
//  SearchView.swift
//  hkbuseta Watch App
//
//  Created by LOOHP on 21/12/2023.
//

import SwiftUI
import shared
import KMPNativeCoroutinesCore
import KMPNativeCoroutinesRxSwift
import KMPNativeCoroutinesAsync
import KMPNativeCoroutinesCombine
import RxSwift

struct SearchView: AppScreenView {
    
    @State var state: RouteKeyboardState
    @State var hasHistory = Shared().hasFavoriteAndLookupRoute()
    
    private let storage: KotlinMutableDictionary<NSString, AnyObject>
    
    private let appContext: AppActiveContextWatchOS
    
    init(appContext: AppActiveContextWatchOS, data: [String: Any], storage: KotlinMutableDictionary<NSString, AnyObject>) {
        self.appContext = appContext
        self.storage = storage
        let input = storage["input"] as? String ?? ""
        self.state = RouteKeyboardState(text: input.isEmpty ? defaultText() : input, nextCharResult: registry(appContext).getPossibleNextChar(input: input))
    }
    
    var body: some View {
        VStack(spacing: 0) {
            Text(Shared().getMtrLineName(lineName: state.text))
                .font(.system(size: 22.scaled(appContext, true)))
                .frame(width: 150.0.scaled(appContext), height: 40.0.scaled(appContext))
                .background { colorInt(0xFF1A1A1A).asColor() }
                .overlay(
                    RoundedRectangle(cornerRadius: 4)
                        .stroke(colorInt(0xFF252525).asColor(), lineWidth: 4.scaled(appContext))
                )
                .padding()
            HStack(spacing: 0) {
                VStack(spacing: 0) {
                    KeyboardKey(content: "1")
                    KeyboardKey(content: "4")
                    KeyboardKey(content: "7")
                    KeyboardKey(content: "<", longContent: "-")
                }.frame(width: 35.scaled(appContext), height: 155.scaled(appContext))
                VStack(spacing: 0) {
                    KeyboardKey(content: "2")
                    KeyboardKey(content: "5")
                    KeyboardKey(content: "8")
                    KeyboardKey(content: "0")
                }.frame(width: 35.scaled(appContext), height: 155.scaled(appContext))
                VStack(spacing: 0) {
                    KeyboardKey(content: "3")
                    KeyboardKey(content: "6")
                    KeyboardKey(content: "9")
                    KeyboardKey(content: "/")
                }.frame(width: 35.scaled(appContext), height: 155.scaled(appContext))
                Spacer().frame(fixedSize: 7.5.scaled(appContext))
                ScrollView(.vertical) {
                    VStack(spacing: 0) {
                        let currentText = state.text
                        if currentText.isEmpty || currentText == defaultText() {
                            KeyboardKey(content: "!")
                        }
                        ForEach(65..<91) { codePoint in
                            let alphabet = Character(UnicodeScalar(codePoint)!)
                            if (!state.nextCharResult.characters.filter { $0.description == alphabet.description }.isEmpty) {
                                KeyboardKey(content: alphabet)
                            }
                        }
                    }.frame(width: 35.scaled(appContext))
                }.frame(height: 155.scaled(appContext))
            }
        }
        .onAppear {
            hasHistory = Shared().hasFavoriteAndLookupRoute()
        }
        .onChange(of: state.text) { _ in
            storage["input"] = state.text == defaultText() ? "" : state.text
        }
    }
    
    func KeyboardKey(content: Character) -> some View {
        KeyboardKey(content: content, longContent: nil)
    }
    
    func KeyboardKey(content: Character, longContent: Character?) -> some View {
        let enabled: Bool
        switch content {
        case "/":
            enabled = state.nextCharResult.hasExactMatch
        case "<", "!":
            enabled = true
        default:
            enabled = !state.nextCharResult.characters.filter { $0.description == content.description }.isEmpty
        }
        let isLookupButton = content == "<" && hasHistory && (state.text.isEmpty || state.text == defaultText())
        return Button(action: {}) {
            switch content {
            case "<":
                if isLookupButton {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.system(size: 17.scaled(appContext, true)))
                        .foregroundColor(colorInt(0xFF03A9F4).asColor())
                } else {
                    Image(systemName: "trash")
                        .font(.system(size: 17.scaled(appContext, true)))
                        .foregroundColor(.red)
                }
            case "/":
                Image(systemName: "checkmark")
                    .font(.system(size: 17.scaled(appContext, true)))
                    .foregroundColor(state.nextCharResult.hasExactMatch ? .green : colorInt(0xFF444444).asColor())
            case "!":
                Image("mtr")
                    .resizable()
                    .frame(width: 20.0.scaled(appContext, true), height: 20.0.scaled(appContext, true))
                    .foregroundColor(.red)
            default:
                Text(content.description)
                    .font(.system(size: 20.scaled(appContext, true), weight: .bold))
                    .foregroundColor(!state.nextCharResult.characters.filter { $0.description == content.description }.isEmpty ? .white : colorInt(0xFF444444).asColor())
            }
        }
        .frame(width: 35.scaled(appContext), height: (content.isLetter || content == "!" ? 35 : 40).scaled(appContext))
        .buttonStyle(PlainButtonStyle())
        .simultaneousGesture(
            LongPressGesture()
                .onEnded { _ in
                    if longContent != nil && !isLookupButton {
                        playHaptics()
                        handleInput(input: longContent!)
                    }
                }
        )
        .highPriorityGesture(
            TapGesture()
                .onEnded { _ in
                    handleInput(input: content)
                }
        )
        .disabled(!enabled)
    }
    
    func handleInput(input: Character) {
        var originalText = state.text
        if originalText == defaultText() {
            originalText = ""
        }

        if input == "/" || input == "!" || (input == "<" && Shared().hasFavoriteAndLookupRoute() && originalText.isEmpty) {
            let result: [RouteSearchResultEntry]
            switch input {
            case "!":
                result = registry(appContext).findRoutes(input: "", exact: false, predicate: Shared().MTR_ROUTE_FILTER)
            case "<":
                result = registry(appContext).findRoutes(input: "", exact: false, coPredicate: Shared().RECENT_ROUTE_FILTER)
            default:
                result = registry(appContext).findRoutes(input: originalText, exact: true)
            }
            if !result.isEmpty {
                let data = newAppDataConatiner()
                data["result"] = result
                if input == "<" {
                    data["recentSort"] = RecentSortMode.forced
                }
                data["listType"] = RouteListType.Companion().RECENT
                appContext.startActivity(appIntent: newAppIntent(appContext, AppScreen.listRoutes, data))
            }
        } else {
            let newText: String
            if input == "<" {
                newText = !originalText.isEmpty ? String(originalText.dropLast()) : originalText
            } else if input == "-" {
                newText = ""
            } else {
                newText = originalText + String(input)
            }
            let possibleNextChar = registry(appContext).getPossibleNextChar(input: newText)
            let text = newText.isEmpty ? defaultText() : newText
            state = RouteKeyboardState(text: text, nextCharResult: possibleNextChar)
        }
    }
}

struct RouteKeyboardState {
    var text: String
    var nextCharResult: Registry.PossibleNextCharResult
}

func defaultText() -> String {
    return Shared().language == "en" ? "Input Route" : "輸入路線"
}
