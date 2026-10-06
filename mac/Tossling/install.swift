import AppKit

let homePath = FileManager.default.homeDirectoryForCurrentUser.path
let bundledCommand = Bundle.main.bundlePath + "/Contents/Resources/tossling/bin/tossling"

func isSymlink(_ path: String) -> Bool {
    (try? FileManager.default.attributesOfItem(atPath: path))?[.type] as? FileAttributeType == .typeSymbolicLink
}

let tossyBundleID = "com.kopylovis.tossy.desktop"

func olderInstallFound() -> Bool {
    let fm = FileManager.default
    let mnrhConfig = homePath + "/.config/mnrh/clip.json"
    if fm.fileExists(atPath: mnrhConfig) && !isSymlink(mnrhConfig) && !fm.fileExists(atPath: defaultConfigFile) { return true }
    let tossyConfig = homePath + "/.config/tossy"
    if fm.fileExists(atPath: tossyConfig + "/config.json") && !isSymlink(tossyConfig) && !fm.fileExists(atPath: defaultConfigFile) { return true }
    let mine = Bundle.main.bundleURL.resolvingSymlinksInPath()
    let apps = [homePath + "/Applications/Tossling.app", homePath + "/Applications/Tossy.app", "/Applications/Tossy.app"]
    if apps.contains(where: { fm.fileExists(atPath: $0) && URL(fileURLWithPath: $0).resolvingSymlinksInPath() != mine }) { return true }
    let support = homePath + "/Library/Application Support/"
    return ["Tossling/Tossling Finder.app", "Tossy/Tossy Finder.app", "mnrh/Tossy.app", "mnrh/mnrh Clip.app", "mnrh/Tossy Finder.app", "mnrh/mnrh Tossy Finder.app"]
        .contains { fm.fileExists(atPath: support + $0) }
}

func adoptOlderInstall() {
    guard olderInstallFound(), FileManager.default.isExecutableFile(atPath: bundledCommand) else { return }
    let p = Process()
    p.executableURL = URL(fileURLWithPath: bundledCommand)
    p.arguments = ["adopt"]
    p.standardOutput = FileHandle.nullDevice
    p.standardError = FileHandle.nullDevice
    guard (try? p.run()) != nil else { return }
    let deadline = Date().addingTimeInterval(90)
    while p.isRunning && Date() < deadline { usleep(200_000) }
    if p.isRunning { p.terminate() }
    let mine = Bundle.main.bundleURL.resolvingSymlinksInPath()
    let strangers = (NSRunningApplication.runningApplications(withBundleIdentifier: Bundle.main.bundleIdentifier ?? "")
        + NSRunningApplication.runningApplications(withBundleIdentifier: tossyBundleID))
        .filter { $0.processIdentifier != getpid() && $0.bundleURL?.resolvingSymlinksInPath() != mine }
    strangers.forEach { $0.terminate() }
    let until = Date().addingTimeInterval(5)
    while strangers.contains(where: { kill($0.processIdentifier, 0) == 0 }) && Date() < until { usleep(200_000) }
    strangers.filter { kill($0.processIdentifier, 0) == 0 }.forEach { $0.forceTerminate() }
}

func linkCommand() {
    let path = Bundle.main.bundlePath
    guard path.hasPrefix("/Applications/") || path.hasPrefix(homePath + "/Applications/") else { return }
    for name in ["tossling", "tossy"] {
        link(name: name, alwaysCreate: name == "tossling")
    }
}

func link(name: String, alwaysCreate: Bool) {
    let fm = FileManager.default
    let link = homePath + "/.local/bin/" + name
    if fm.fileExists(atPath: link) || isSymlink(link) {
        guard isSymlink(link), (try? fm.destinationOfSymbolicLink(atPath: link)) != bundledCommand else { return }
    } else if !alwaysCreate || fm.fileExists(atPath: "/opt/homebrew/bin/" + name) || fm.fileExists(atPath: "/usr/local/bin/" + name) {
        return
    }
    try? fm.createDirectory(atPath: homePath + "/.local/bin", withIntermediateDirectories: true)
    try? fm.removeItem(atPath: link)
    try? fm.createSymbolicLink(atPath: link, withDestinationPath: bundledCommand)
}

func refuseTranslocated() {
    let path = Bundle.main.bundlePath
    guard path.contains("/AppTranslocation/") || path.hasPrefix("/Volumes/") else { return }
    NSApplication.shared.setActivationPolicy(.regular)
    NSApp.activate(ignoringOtherApps: true)
    let alert = NSAlert()
    alert.messageText = L("Перенеси Tossling в «Программы»", "Move Tossling to Applications")
    alert.informativeText = L("Перетащи Tossling в папку «Программы» и открой его оттуда: из загрузок или с образа диска он не сможет работать в фоне.",
                              "Drag Tossling into the Applications folder and open it from there: from Downloads or the disk image it cannot run in the background.")
    alert.runModal()
    exit(0)
}

func prepareInstall() {
    refuseTranslocated()
    adoptOlderInstall()
    linkCommand()
}
