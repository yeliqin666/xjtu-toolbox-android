package com.xjtu.toolbox.util

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xjtu.toolbox.classreplay.DownloadTaskDao
import com.xjtu.toolbox.classreplay.DownloadTaskEntity
import com.xjtu.toolbox.schedule.CustomCourseDao
import com.xjtu.toolbox.schedule.CustomCourseEntity

@Database(
    entities = [CustomCourseEntity::class, DownloadTaskEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customCourseDao(): CustomCourseDao
    abstract fun downloadTaskDao(): DownloadTaskDao

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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "xjtu_toolbox.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
