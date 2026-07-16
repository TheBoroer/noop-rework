import Foundation

extension WhoopStore {
    // Backend-aware (Phase 2c-2 Task 4): routes through `OutboxBridge.outboxSetCursor`/`outboxCursor`
    // (built fresh per call, see the note atop `RawOutbox.swift`'s Public API section). Cursor-name
    // prefixing (`"highwater:"`, `"read:"`) stays caller-side, unchanged, in the four convenience
    // wrappers below: they just compute the prefixed name and delegate to `setCursor`/`cursor`.
    public func setCursor(_ name: String, _ value: Int) async throws {
        switch backend {
        case .room(let room):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = room; throw RoomBackendUnavailableError()
            #else
            try await OutboxBridge(db: room).outboxSetCursor(name, value)
            #endif
        }
    }

    public func cursor(_ name: String) async throws -> Int? {
        switch backend {
        case .room(let room):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = room; throw RoomBackendUnavailableError()
            #else
            return try await OutboxBridge(db: room).outboxCursor(name)
            #endif
        }
    }

    public func setHighwater(_ stream: String, _ ts: Int) async throws { try await setCursor("highwater:" + stream, ts) }
    public func highwater(_ stream: String) async throws -> Int? { try await cursor("highwater:" + stream) }

    // MARK: - Read highwater (server-pull cursor)
    // A DISTINCT "read:" prefix so the pull cursor never collides with the upload "highwater:"
    // cursor for the same stream. Tracks the max server ts pulled-and-upserted per stream so
    // pulls are incremental.
    public func setReadHighwater(_ stream: String, _ ts: Int) async throws { try await setCursor("read:" + stream, ts) }
    public func readHighwater(_ stream: String) async throws -> Int? { try await cursor("read:" + stream) }
}
