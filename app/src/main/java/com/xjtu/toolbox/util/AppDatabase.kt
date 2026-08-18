package com.xjtu.toolbox.util

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xjtu.toolbox.classreplay.DownloadTaskDao
import com.xjtu.toolbox.classreplay.DownloadTaskEntity
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfDao
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfEntity
import com.xjtu.toolbox.schedule.CustomCourseDao
import com.xjtu.toolbox.schedule.CustomCourseEntity

@Database(
    entities = [
        CustomCourseEntity::class,
        DownloadTaskEntity::class,
        Jiaocai1ShelfEntity::class,
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customCourseDao(): CustomCourseDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun jiaocai1ShelfDao(): Jiaocai1ShelfDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Migration 1→2: 添加 download_tasks 表
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS download_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        activityId INTEGER NOT NULL,
                        courseName TEXT NOT NULL,
                        activityTitle TEXT NOT NULL,
                        cameraType TEXT NOT NULL,
                        videoUrl TEXT NOT NULL,
                        audioSource TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        fileSize INTEGER NOT NULL,
                        downloadedSize INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        createTime INTEGER NOT NULL,
                        completeTime INTEGER,
                        errorMessage TEXT,
                        downloadSpeed INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }
        
        // Migration 2→3: 如果 downloadSpeed 列不存在则添加
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    database.execSQL("ALTER TABLE download_tasks ADD COLUMN downloadSpeed INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {
                    // 列已存在，忽略
                }
            }
        }

        // Migration 3→4: custom_courses 增加分钟级时间列
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    database.execSQL("ALTER TABLE custom_courses ADD COLUMN startMinuteOfDay INTEGER NOT NULL DEFAULT -1")
                } catch (e: Exception) {
                    // 列已存在，忽略
                }
                try {
                    database.execSQL("ALTER TABLE custom_courses ADD COLUMN endMinuteOfDay INTEGER NOT NULL DEFAULT -1")
                } catch (e: Exception) {
                    // 列已存在，忽略
                }
            }
        }

        // Migration 4→5: custom_courses 增加 accountId 列，多账号隔离。
        // 回填值由 AccountMigration 在迁移完成后通过 update 语句写入当前 active accountId；
        // 此处仅保证列存在且默认空串，避免破坏旧数据读取。
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    database.execSQL("ALTER TABLE custom_courses ADD COLUMN accountId TEXT NOT NULL DEFAULT ''")
                } catch (e: Exception) {
                    // 列已存在，忽略
                }
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS jiaocai1_shelf (
                        ssno TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        author TEXT NOT NULL,
                        coverUrl TEXT NOT NULL,
                        totalPages INTEGER NOT NULL,
                        lastReadIndex INTEGER NOT NULL,
                        lastReadAt INTEGER NOT NULL,
                        addedAt INTEGER NOT NULL,
                        pinned INTEGER NOT NULL,
                        cachedPages INTEGER NOT NULL,
                        cropLeft REAL NOT NULL,
                        cropTop REAL NOT NULL,
                        cropRight REAL NOT NULL,
                        cropBottom REAL NOT NULL,
                        cropReady INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS jiaocai1_cache_pages (
                        ssno TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        ready INTEGER NOT NULL,
                        failCount INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(ssno, fileName)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * 移除整本离线缓存留下的痕迹：pinned / cachedPages / crop* 六列已无人读写，
         * jiaocai1_cache_pages 更是专为记录整本下载进度而建。SQLite 不支持 DROP
         * COLUMN，只能建新表拷数据再换名。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS jiaocai1_cache_pages")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS jiaocai1_shelf_new (
                        ssno TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        author TEXT NOT NULL,
                        coverUrl TEXT NOT NULL,
                        totalPages INTEGER NOT NULL,
                        lastReadIndex INTEGER NOT NULL,
                        lastReadAt INTEGER NOT NULL,
                        addedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT OR REPLACE INTO jiaocai1_shelf_new
                        (ssno, title, author, coverUrl, totalPages, lastReadIndex, lastReadAt, addedAt)
                    SELECT ssno, title, author, coverUrl, totalPages, lastReadIndex, lastReadAt, addedAt
                    FROM jiaocai1_shelf
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE jiaocai1_shelf")
                database.execSQL("ALTER TABLE jiaocai1_shelf_new RENAME TO jiaocai1_shelf")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "xjtu_toolbox.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
