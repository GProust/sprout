import Foundation
import GRDB

/// The local database (ADR-0002: everything stays on the device).
///
/// The schema is **Android's**, at the version Room had reached when iOS
/// started — the `CREATE TABLE` statements below are copied from
/// `android/app/schemas/…/16.json` rather than rewritten with a table builder,
/// so the two are comparable by reading them side by side.
///
/// iOS does not replay Android's fourteen migrations. There is no iPhone with an
/// older Sprout database on it, so there is nothing to migrate *from*; this
/// starts at 16 and every change after it gets a migration of its own, with the
/// same rule Android has (ADR-0002): hand-written, no destructive fallback,
/// because a parent cannot re-enter this history.
public enum SproutDatabase {

    /// The Android schema version this starts from.
    public static let initialAndroidSchemaVersion = 16

    /// Opens the database at `path`, creating and migrating it as needed.
    public static func open(atPath path: String) throws -> DatabaseQueue {
        var configuration = Configuration()
        // Room enforces these; SQLite does not by default, and a foreign key
        // that is only enforced on one of the two phones is not enforced.
        configuration.foreignKeysEnabled = true
        let queue = try DatabaseQueue(path: path, configuration: configuration)
        try migrator.migrate(queue)
        return queue
    }

    /// An in-memory database, for tests and previews.
    public static func inMemory() throws -> DatabaseQueue {
        let queue = try DatabaseQueue()
        try migrator.migrate(queue)
        return queue
    }

    public static var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()

        migrator.registerMigration("v16-android-schema") { db in
            for statement in schemaV16 {
                try db.execute(sql: statement)
            }
        }

        return migrator
    }

    /// Verbatim from Room's exported schema, version 16.
    ///
    /// Changing a line here changes the shape of a shipped database. If Android's
    /// schema moves, this does not follow automatically — add a migration below
    /// rather than editing these.
    static let schemaV16: [String] = [
        """
        CREATE TABLE IF NOT EXISTS `baby` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `birthDate` INTEGER NOT NULL,
            `archived` INTEGER NOT NULL DEFAULT 0,
            `feedingReminderEnabled` INTEGER,
            `feedingReminderIntervalMinutes` INTEGER,
            `uid` TEXT NOT NULL DEFAULT '',
            `updatedAt` INTEGER NOT NULL DEFAULT 0,
            `deletedAt` INTEGER)
        """,
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_baby_uid` ON `baby` (`uid`)",

        """
        CREATE TABLE IF NOT EXISTS `feeding` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `babyId` INTEGER NOT NULL DEFAULT 1,
            `type` TEXT NOT NULL,
            `side` TEXT,
            `amountMl` INTEGER,
            `amountGrams` INTEGER,
            `startTime` INTEGER NOT NULL,
            `endTime` INTEGER,
            `leftDurationMs` INTEGER,
            `rightDurationMs` INTEGER,
            `segments` TEXT NOT NULL DEFAULT '',
            `notes` TEXT,
            `uid` TEXT NOT NULL DEFAULT '',
            `updatedAt` INTEGER NOT NULL DEFAULT 0,
            `deletedAt` INTEGER)
        """,
        "CREATE INDEX IF NOT EXISTS `index_feeding_babyId` ON `feeding` (`babyId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_feeding_uid` ON `feeding` (`uid`)",

        """
        CREATE TABLE IF NOT EXISTS `sleep` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `babyId` INTEGER NOT NULL DEFAULT 1,
            `startTime` INTEGER NOT NULL,
            `endTime` INTEGER,
            `position` TEXT,
            `place` TEXT,
            `placeNote` TEXT,
            `notes` TEXT,
            `uid` TEXT NOT NULL DEFAULT '',
            `updatedAt` INTEGER NOT NULL DEFAULT 0,
            `deletedAt` INTEGER)
        """,
        "CREATE INDEX IF NOT EXISTS `index_sleep_babyId` ON `sleep` (`babyId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sleep_uid` ON `sleep` (`uid`)",

        """
        CREATE TABLE IF NOT EXISTS `diaper` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `babyId` INTEGER NOT NULL DEFAULT 1,
            `time` INTEGER NOT NULL,
            `wet` INTEGER NOT NULL DEFAULT 0,
            `dirty` INTEGER NOT NULL DEFAULT 0,
            `stoolColor` TEXT,
            `notes` TEXT,
            `uid` TEXT NOT NULL DEFAULT '',
            `updatedAt` INTEGER NOT NULL DEFAULT 0,
            `deletedAt` INTEGER)
        """,
        "CREATE INDEX IF NOT EXISTS `index_diaper_babyId` ON `diaper` (`babyId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_diaper_uid` ON `diaper` (`uid`)",

        """
        CREATE TABLE IF NOT EXISTS `growth` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `babyId` INTEGER NOT NULL DEFAULT 1,
            `time` INTEGER NOT NULL,
            `weightGrams` INTEGER,
            `heightMm` INTEGER,
            `headMm` INTEGER,
            `notes` TEXT,
            `uid` TEXT NOT NULL DEFAULT '',
            `updatedAt` INTEGER NOT NULL DEFAULT 0,
            `deletedAt` INTEGER)
        """,
        "CREATE INDEX IF NOT EXISTS `index_growth_babyId` ON `growth` (`babyId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_growth_uid` ON `growth` (`uid`)",

        """
        CREATE TABLE IF NOT EXISTS `treatment` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `babyId` INTEGER NOT NULL,
            `name` TEXT NOT NULL,
            `dose` TEXT,
            `intervalDays` INTEGER NOT NULL,
            `timesOfDay` TEXT NOT NULL,
            `startDate` INTEGER NOT NULL,
            `endDate` INTEGER,
            `remindersEnabled` INTEGER NOT NULL,
            `active` INTEGER NOT NULL,
            `notes` TEXT,
            `uid` TEXT NOT NULL DEFAULT '',
            `updatedAt` INTEGER NOT NULL DEFAULT 0,
            `deletedAt` INTEGER)
        """,
        "CREATE INDEX IF NOT EXISTS `index_treatment_babyId` ON `treatment` (`babyId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_treatment_uid` ON `treatment` (`uid`)",

        """
        CREATE TABLE IF NOT EXISTS `pumping` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `time` INTEGER NOT NULL,
            `amountMl` INTEGER NOT NULL,
            `side` TEXT,
            `storage` TEXT NOT NULL,
            `notes` TEXT,
            `uid` TEXT NOT NULL DEFAULT '',
            `updatedAt` INTEGER NOT NULL DEFAULT 0,
            `deletedAt` INTEGER)
        """,
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_pumping_uid` ON `pumping` (`uid`)",

        """
        CREATE TABLE IF NOT EXISTS `wellbeing` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `time` INTEGER NOT NULL,
            `mood` INTEGER NOT NULL,
            `bleeding` TEXT,
            `recovery` TEXT,
            `breast` TEXT,
            `notes` TEXT)
        """,

        """
        CREATE TABLE IF NOT EXISTS `parent_profile` (
            `id` INTEGER NOT NULL,
            `name` TEXT NOT NULL,
            `gaveBirth` INTEGER NOT NULL,
            `breastfeeding` INTEGER NOT NULL,
            `deliveryType` TEXT,
            `askHealing` INTEGER NOT NULL DEFAULT 1,
            `askBleeding` INTEGER NOT NULL DEFAULT 1,
            `askBreasts` INTEGER NOT NULL DEFAULT 1,
            `trackWellbeing` INTEGER NOT NULL DEFAULT 1,
            `lastCheckIn` INTEGER,
            `activeBabyId` INTEGER,
            PRIMARY KEY(`id`))
        """,

        """
        CREATE TABLE IF NOT EXISTS `tombstone` (
            `uid` TEXT NOT NULL,
            `entity` TEXT NOT NULL,
            `deletedAt` INTEGER NOT NULL,
            PRIMARY KEY(`uid`))
        """,
    ]
}
