import Foundation
import GRDB

extension WhoopStore {
    // Backend-aware (Phase 2c-2 Task 4): `.room` routes through `OutboxBridge.outboxSetCursor`/
    // `outboxCursor` (built fresh per call, see the note atop `RawOutbox.swift`'s Public API section);
    // `.legacyGrdb` keeps the exact GRDB SQL below. Cursor-name prefixing (`"highwater:"`, `"read:"`)
    // stays caller-side, unchanged, in the four convenience wrappers below: they just compute the
    // prefixed name and delegate to `setCursor`/`cursor`, so making those two backend-aware
    // automatically routes prefixed calls to the right backend with no further edits here.
    public func setCursor(_ name: String, _ value: Int) async throws {
        switch backend {
        case .room(let room):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = room; throw RoomBackendUnavailableError()
            #else
            try await OutboxBridge(db: room).outboxSetCursor(name, value)
            #endif
        case .legacyGrdb:
            try setCursorGrdb(name, value)
        }
    }

    private func setCursorGrdb(_ name: String, _ value: Int) throws {
        try syncWrite { db in
            try db.execute(sql: """
                INSERT INTO cursors (name, value) VALUES (?, ?)
                ON CONFLICT(name) DO UPDATE SET value = excluded.value
                """, arguments: [name, value])
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
        case .legacyGrdb:
            return try cursorGrdb(name)
        }
    }

    private func cursorGrdb(_ name: String) throws -> Int? {
        try syncRead { db in
            try Int.fetchOne(db, sql: "SELECT value FROM cursors WHERE name = ?", arguments: [name])
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
