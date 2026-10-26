import SwiftUI

@main
struct iOSApp: App {
    @State private var isLoaded = false

    init() {
        PotsLiteRTBridgeInstaller.install()
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                Color("LaunchBackground")
                    .ignoresSafeArea()
                ContentView()
            }
        }
    }
}
