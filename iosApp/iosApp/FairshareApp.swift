import SwiftUI
import UIKit
import FairshareShared

@main
struct FairshareApp: App {
    var body: some Scene {
        WindowGroup {
            StartupView()
        }
    }
}

// Commit a native first frame before creating the Kotlin runtime / Compose controller.
// Keep it underneath the controller until Compose has painted its own first frame.
private struct StartupView: View {
    @State private var startShared = false
    @State private var sharedBackground: UIColor?
    @State private var sharedColorScheme: ColorScheme?

    var body: some View {
        ZStack {
            (sharedBackground.map { Color(uiColor: $0) } ?? Color("LaunchBackground")).ignoresSafeArea()
            VStack(spacing: 0) {
                Image("LaunchLogo").resizable().frame(width: 104, height: 104)
                (Text("Zero").fontWeight(.semibold) + Text("IT").fontWeight(.heavy))
                    .font(.system(size: 30, design: .default)).tracking(-0.7).padding(.top, 20)
                Text("Opening your ledger…").font(.system(size: 14)).padding(.top, 16)
            }.foregroundStyle(Color(red: 0.11, green: 0.11, blue: 0.12))
            if startShared {
                // Keep UIKit's top/side safe area (clock, Dynamic Island and rotation).
                // Compose owns bottom navigation and keyboard avoidance itself.
                SharedView { argb, mode in
                    let bits = UInt32(bitPattern: argb.int32Value)
                    let red = CGFloat((bits >> 16) & 255) / 255
                    let green = CGFloat((bits >> 8) & 255) / 255
                    let blue = CGFloat(bits & 255) / 255
                    // Updating SwiftUI state during Compose's draw is deferred.
                    DispatchQueue.main.async {
                        let color = UIColor(red: red, green: green, blue: blue, alpha: 1)
                        if sharedBackground != color { sharedBackground = color }
                        // SYSTEM must remain unforced so OS appearance changes propagate.
                        sharedColorScheme = mode == "DARK" ? .dark : mode == "LIGHT" ? .light : nil
                    }
                }.ignoresSafeArea(.all, edges: .bottom)
            }
        }
        .preferredColorScheme(sharedColorScheme)
        .task {
            // Give Core Animation a frame to display native feedback before Kotlin starts.
            do { try await Task.sleep(nanoseconds: 50_000_000) } catch { return }
            startShared = true
        }
    }
}

private struct SharedView: UIViewControllerRepresentable {
    let onBackgroundChanged: (KotlinInt, String) -> Void
    func makeUIViewController(context: Context) -> UIViewController {
        // UI acceptance gets a separate database; release launches always use real storage.
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        let databaseName: String
        if let flag = arguments.firstIndex(of: "--fairshare-ui-test-database"), arguments.indices.contains(flag + 1) {
            databaseName = arguments[flag + 1]
        } else {
            databaseName = ProcessInfo.processInfo.environment["FAIRSHARE_UI_TEST_DATABASE"] ?? "fairshare.db"
        }
        #else
        let databaseName = "fairshare.db"
        #endif
        let controller = MainViewControllerKt.MainViewController(databaseName: databaseName, onBackgroundChanged: onBackgroundChanged)
        controller.view.backgroundColor = .clear
        controller.view.isOpaque = false
        return controller
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
