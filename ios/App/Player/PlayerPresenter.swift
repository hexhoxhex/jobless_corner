import AVKit
import SwiftUI
import UIKit

/// Shows Apple's own full-screen player, presented the way the Apple TV
/// app does it: its close button, AirPlay button, picture in picture and
/// scrubbing, with the title on it and on the AirPlay receiver.
///
/// Background rules (Apple's "playing audio from a video asset in the
/// background"): when the app leaves the screen without picture in picture
/// (the phone locks), the player is taken off the view so the sound and any
/// AirPlay keep going, and put back when the app returns.
@MainActor
final class PlayerPresenter: NSObject, AVPlayerViewControllerDelegate {
    static let shared = PlayerPresenter()
    private var controller: AVPlayerViewController?
    private var inPictureInPicture = false
    private weak var model: PlayerModel?

    private override init() {
        super.init()
        let nc = NotificationCenter.default
        nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.enteredBackground() }
        }
        nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.enteringForeground() }
        }
    }

    func show(_ model: PlayerModel) {
        self.model = model
        if let vc = controller, vc.presentingViewController != nil || inPictureInPicture { return }
        let vc = controller ?? makeController(model)
        controller = vc
        guard let top = Self.topController(), top !== vc else { return }
        top.present(vc, animated: true)
    }

    private func makeController(_ model: PlayerModel) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = model.player
        vc.delegate = self
        vc.modalPresentationStyle = .fullScreen
        vc.allowsPictureInPicturePlayback = true
        vc.canStartPictureInPictureAutomaticallyFromInline = true
        vc.updatesNowPlayingInfoCenter = false       // PlayerModel fills it in
        if let overlay = vc.contentOverlayView {
            let host = UIHostingController(rootView: StatusBadge().environmentObject(model))
            host.view.backgroundColor = .clear
            host.view.isUserInteractionEnabled = false
            host.view.translatesAutoresizingMaskIntoConstraints = false
            overlay.addSubview(host.view)
            NSLayoutConstraint.activate([
                host.view.leadingAnchor.constraint(equalTo: overlay.leadingAnchor),
                host.view.trailingAnchor.constraint(equalTo: overlay.trailingAnchor),
                host.view.topAnchor.constraint(equalTo: overlay.topAnchor),
                host.view.bottomAnchor.constraint(equalTo: overlay.bottomAnchor),
            ])
            vc.addChild(host)
            host.didMove(toParent: vc)
        }
        return vc
    }

    private static func topController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        let window = scene?.windows.first { $0.isKeyWindow } ?? scene?.windows.first
        var top = window?.rootViewController
        while let next = top?.presentedViewController { top = next }
        return top
    }

    // MARK: background

    private func enteredBackground() {
        guard let vc = controller, !inPictureInPicture else { return }
        vc.player = nil
    }

    private func enteringForeground() {
        guard let vc = controller, let model, vc.player == nil else { return }
        vc.player = model.player
    }

    // MARK: AVPlayerViewControllerDelegate

    /// The viewer closed the player (not a move into picture in picture).
    nonisolated func playerViewController(_ playerViewController: AVPlayerViewController,
                                          willEndFullScreenPresentationWithAnimationCoordinator coordinator: UIViewControllerTransitionCoordinator) {
        coordinator.animate(alongsideTransition: nil) { context in
            guard !context.isCancelled else { return }
            Task { @MainActor in
                let presenter = PlayerPresenter.shared
                if !presenter.inPictureInPicture { presenter.model?.stop() }
            }
        }
    }

    nonisolated func playerViewControllerWillStartPictureInPicture(_ playerViewController: AVPlayerViewController) {
        Task { @MainActor in PlayerPresenter.shared.inPictureInPicture = true }
    }

    nonisolated func playerViewControllerDidStopPictureInPicture(_ playerViewController: AVPlayerViewController) {
        Task { @MainActor in PlayerPresenter.shared.inPictureInPicture = false }
    }

    /// Back from picture in picture: put the full-screen player back.
    nonisolated func playerViewController(_ playerViewController: AVPlayerViewController,
                                          restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        Task { @MainActor in
            let presenter = PlayerPresenter.shared
            guard let vc = presenter.controller else { completionHandler(false); return }
            if vc.presentingViewController != nil { completionHandler(true); return }
            guard let top = Self.topController() else { completionHandler(false); return }
            top.present(vc, animated: true) { completionHandler(true) }
        }
    }
}

/// What the player is doing, over the picture: "Finding a source…",
/// "Reconnecting…", or why nothing is playing.
struct StatusBadge: View {
    @EnvironmentObject var player: PlayerModel

    var body: some View {
        VStack {
            if !player.status.isEmpty {
                HStack(spacing: 10) {
                    if player.working { ProgressView().tint(.white) }
                    Text(player.status)
                        .font(.subheadline.weight(.medium))
                        .multilineTextAlignment(.leading)
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(.black.opacity(0.65), in: Capsule())
                .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 24)
        .animation(.easeOut(duration: 0.2), value: player.status)
    }
}
