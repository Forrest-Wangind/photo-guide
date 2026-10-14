import SwiftUI
import ComposeApp

/// 把共享的 Compose 界面包成 SwiftUI 视图。
/// 取景器本身在 Kotlin 侧由 UIKitView 承载（AVCaptureVideoPreviewLayer），
/// 所以这里只需要一个不做任何裁切的容器。
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
    }
}
