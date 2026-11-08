package de.chromefiles.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.chromefiles.app.data.local.ChromeFilesDao
import de.chromefiles.app.data.local.ChromeFilesDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideChromeFilesDatabase(
        @ApplicationContext context: Context
    ): ChromeFilesDatabase {
        return Room.databaseBuilder(
            context,
            ChromeFilesDatabase::class.java,
            "chromefiles_pro_fts.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideChromeFilesDao(database: ChromeFilesDatabase): ChromeFilesDao {
        return database.chromeFilesDao()
    }
}
