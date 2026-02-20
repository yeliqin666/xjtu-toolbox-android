package com.xjtu.toolbox.util

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.xjtu.toolbox.schedule.CustomCourseDao
import com.xjtu.toolbox.schedule.CustomCourseEntity

@Database(
    entities = [CustomCourseEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customCourseDao(): CustomCourseDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "xjtu_toolbox.db"
                )
                    // 不使用 fallbackToDestructiveMigration()，避免版本升级时静默丢失用户数据
                    // 未来升级 version 时，必须编写显式 Migration（如 Migration(1, 2)）
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
