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

    /// Migrations registered after the Android schema, in the order they run.
    ///
    /// The list exists so that ``schemaVersion`` cannot drift from it: adding a
    /// migration here moves the number a replica carries, with nothing else to
    /// remember to edit.
    static let migrationsAfterAndroidSchema: [(name: String, migrate: @Sendable (Database) throws -> Void)] = [
        (name: "v17-as-needed-medicine", migrate: migrateToV17),
        (name: "v18-medicine-amounts", migrate: migrateToV18),
    ]

    /// v17 -> v18: amounts on an as-needed medicine, and on the dose that used
    /// them (BDR-18).
    ///
    /// Android's `MIGRATION_17_18`, verbatim. Four added columns and nothing
    /// rewritten: a medicine set up before this reads back with every amount
    /// nil, which is the truth — it was never measured in anything, and
    /// inventing a zero would put it into arithmetic it was never part of.
    ///
    /// `minIntervalMinutes` does not move. It gains a meaning instead: zero is
    /// "no gap rule", which older rows cannot hold because neither editor ever
    /// let one be typed.
    private static func migrateToV18(_ db: Database) throws {
        for statement in schemaV18 {
            try db.execute(sql: statement)
        }
    }

    static let schemaV18: [String] = [
        "ALTER TABLE `medicine` ADD COLUMN `doseAmount` REAL",
        "ALTER TABLE `medicine` ADD COLUMN `doseUnit` TEXT",
        "ALTER TABLE `medicine` ADD COLUMN `maxAmountPerDay` REAL",
        "ALTER TABLE `medicine_dose` ADD COLUMN `amount` REAL",
    ]

    /// v16 -> v17: the two tables behind as-needed medicine (BDR-15).
    ///
    /// The statements are Android's `MIGRATION_16_17`, verbatim, for the same
    /// reason the v16 schema above is Room's export verbatim: the two are
    /// comparable by reading them side by side, and a column that differs by a
    /// default or a nullability is a merge that half-works.
    ///
    /// `medicine_dose.medicineUid` is a uid rather than a foreign key to
    /// `medicine.id`, and deliberately not a SQL foreign key either: a dose can
    /// arrive from a merge before the medicine it names, and the constraint
    /// would reject the row rather than let the next exchange complete it —
    /// which matters more here than on Android, because `foreignKeysEnabled` is
    /// on in this app's configuration.
    private static func migrateToV17(_ db: Database) throws {
        for statement in schemaV17 {
            try db.execute(sql: statement)
        }
    }

    static let schemaV17: [String] = [
        """
        CREATE TABLE IF NOT EXISTS `medicine` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `babyId` INTEGER NOT NULL,
            `name` TEXT NOT NULL,
            `dose` TEXT,
            `minIntervalMinutes` INTEGER NOT NULL,
            `comfortIntervalMinutes` INTEGER,
            `maxPerDay` INTEGER,
            `remindWhenDue` INTEGER NOT NULL,
            `remindAtComfort` INTEGER NOT NULL,
            `active` INTEGER NOT NULL,
            `notes` TEXT,
            `uid` TEXT NOT NULL DEFAULT '',
            `updatedAt` INTEGER NOT NULL DEFAULT 0,
            `deletedAt` INTEGER)
        """,
        "CREATE INDEX IF NOT EXISTS `index_medicine_babyId` ON `medicine` (`babyId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_medicine_uid` ON `medicine` (`uid`)",

        """
        CREATE TABLE IF NOT EXISTS `medicine_dose` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `babyId` INTEGER NOT NULL,
            `medicineUid` TEXT NOT NULL,
            `time` INTEGER NOT NULL,
            `notes` TEXT,
            `uid` TEXT NOT NULL DEFAULT '',
            `updatedAt` INTEGER NOT NULL DEFAULT 0,
            `deletedAt` INTEGER)
        """,
        "CREATE INDEX IF NOT EXISTS `index_medicine_dose_babyId` ON `medicine_dose` (`babyId`)",
        "CREATE INDEX IF NOT EXISTS `index_medicine_dose_medicineUid` ON `medicine_dose` (`medicineUid`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_medicine_dose_uid` ON `medicine_dose` (`uid`)",
    ]

    /// The schema version this build speaks, in **Android's** numbering.
    ///
    /// It travels in every replica and is what the other phone compares against,
    /// so the two apps have to count in the same units. Android reads it off the
    /// database; GRDB names its migrations rather than numbering them, so here it
    /// is derived from the list above instead — which is the same thing said a
    /// different way, not a constant beside it that could disagree.
    public static var schemaVersion: Int {
        initialAndroidSchemaVersion + migrationsAfterAndroidSchema.count
    }

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

        for step in migrationsAfterAndroidSchema {
            migrator.registerMigration(step.name, migrate: step.migrate)
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
