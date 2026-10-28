import ManagedSettings

/// Handles the two buttons on the "Nooo" screen.
class ShieldActionExtension: ShieldActionDelegate {

    override func handle(action: ShieldAction, for application: ApplicationToken, completionHandler: @escaping (ShieldActionResponse) -> Void) {
        completionHandler(respond(to: action))
    }

    override func handle(action: ShieldAction, for webDomain: WebDomainToken, completionHandler: @escaping (ShieldActionResponse) -> Void) {
        completionHandler(respond(to: action))
    }

    override func handle(action: ShieldAction, for category: ActivityCategoryToken, completionHandler: @escaping (ShieldActionResponse) -> Void) {
        completionHandler(respond(to: action))
    }

    private func respond(to action: ShieldAction) -> ShieldActionResponse {
        switch action {
        case .primaryButtonPressed:
            // "Fine, close it": the app closes, back to the home screen.
            return .close
        case .secondaryButtonPressed:
            // "Open anyway": the screen comes down for a while, then the monitor extension puts it back.
            Shields.pause()
            return .none
        @unknown default:
            return .close
        }
    }
}
