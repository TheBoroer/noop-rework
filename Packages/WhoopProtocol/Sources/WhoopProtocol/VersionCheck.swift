import Foundation
import Shared

/// Version-string comparison for the in-app "Check for updates". Lives here because this package is
/// the one with a unit-test target; the comparison is the only part with a real bug risk (a plain
/// string compare gets `1.40` vs `1.39` and `1.9` vs `1.10` WRONG). Delegates to the shared Kotlin
/// implementation (`com.noop.update.VersionCompare`, commonMain) so both platforms share one
/// comparator.
public enum VersionCheck {

    /// True iff `latest` is a strictly newer version than `current`. Compares dot-separated numeric
    /// segments left to right (so `1.40 > 1.39` and `1.9 < 1.10`). Tolerant of a leading "v" and any
    /// non-numeric suffix (e.g. build metadata or a "-demo" flavour tag).
    public static func isNewer(_ latest: String, than current: String) -> Bool {
        VersionCompare.shared.isNewer(latest: latest, current: current)
    }
}
