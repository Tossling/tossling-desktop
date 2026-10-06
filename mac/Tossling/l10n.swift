import Foundation

var uiRussian = Locale.preferredLanguages.first?.hasPrefix("ru") == true

func L(_ ru: String, _ en: String) -> String {
    uiRussian ? ru : en
}
