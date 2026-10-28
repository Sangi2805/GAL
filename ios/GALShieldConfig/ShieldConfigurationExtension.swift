import ManagedSettings
import ManagedSettingsUI
import UIKit

/// Draws the "Nooo" screen iOS shows in front of a picked app: the elephant, a roast that names the app, and
/// two buttons. Apple only allows a picture, two lines of text and two buttons here.
class ShieldConfigurationExtension: ShieldConfigurationDataSource {

    override func configuration(shielding application: Application) -> ShieldConfiguration {
        make(name: application.localizedDisplayName)
    }

    override func configuration(shielding application: Application, in category: ActivityCategory) -> ShieldConfiguration {
        make(name: application.localizedDisplayName)
    }

    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration {
        make(name: webDomain.domain)
    }

    override func configuration(shielding webDomain: WebDomain, in category: ActivityCategory) -> ShieldConfiguration {
        make(name: webDomain.domain)
    }

    private func make(name: String?) -> ShieldConfiguration {
        let ink = UIColor(red: 0.227, green: 0.082, blue: 0.188, alpha: 1)
        let pink = UIColor(red: 1.0, green: 0.361, blue: 0.541, alpha: 1)
        return ShieldConfiguration(
            backgroundBlurStyle: .systemThickMaterialLight,
            backgroundColor: UIColor(red: 1.0, green: 0.969, blue: 0.984, alpha: 1),
            icon: UIImage(named: "ShieldElephant"),
            title: ShieldConfiguration.Label(text: "Nooo.", color: ink),
            subtitle: ShieldConfiguration.Label(text: Phrases.appRoast(appName: name), color: ink),
            primaryButtonLabel: ShieldConfiguration.Label(text: "Fine, close it", color: .white),
            primaryButtonBackgroundColor: pink,
            secondaryButtonLabel: ShieldConfiguration.Label(text: "Open anyway (\(Shields.pauseMinutes) min)", color: pink)
        )
    }
}
