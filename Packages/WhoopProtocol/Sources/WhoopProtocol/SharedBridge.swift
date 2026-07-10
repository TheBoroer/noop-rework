import Shared

/// Bridge marker proving the Kotlin shared framework links into this package.
/// Real delegation lands file by file; see the unification design doc.
enum SharedBridge {
    /// Kotlin: com.noop.update.UpdateCheck-adjacent pure helper is NOT exposed;
    /// use a trivially pure shared symbol to pin linkage.
    static func kotlinLinkProbe() -> String {
        // SharedSmoke lives in commonMain since Phase 1 Task 4.
        SharedSmoke.shared.MODULE
    }
}
