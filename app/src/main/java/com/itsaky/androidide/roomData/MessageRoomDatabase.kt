/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.roomData

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Annotates class to be a Room Database with a table (entity) of the Message class
  @Database(entities = arrayOf(Message::class), version = 1, exportSchema = false)
  public abstract class MessageRoomDatabase : RoomDatabase() {

    abstract fun MessageDao(): MessageDao

  private class MessageDatabaseCallback(
    private val scope: CoroutineScope
  ) : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
      super.onCreate(db)
      INSTANCE?.let { database ->
        scope.launch {
          populateDatabase(database.MessageDao())
        }
      }
    }

    suspend fun populateDatabase(MessageDao: MessageDao) {
      // Delete all content here.
      MessageDao.deleteAll()

      MessageDao.insert(Message(0, 1, "This is a test 1"))
      MessageDao.insert(Message(0, 2, "This is a test 2"))
      MessageDao.insert(Message(0, 3, "This is a test 3"))
      MessageDao.insert(Message(0, 4, "This is a test 4"))
      MessageDao.insert(Message(0, 5, "This is a test 5"))
      MessageDao.insert(Message(0, 6, "This is a test 6"))
      MessageDao.insert(Message(0, 7, "This is a test 7"))
      MessageDao.insert(Message(0, 8, "This is a test 8"))
      MessageDao.insert(Message(0, 9, "This is a test 9"))
      MessageDao.insert(Message(0, 10, "This is a test 10"))
      val messageList : List<Message> = MessageDao.getAlphabetizedMessages()
      messageList.forEach{ msg -> Log.d("MessageRoomDatabase", "after insert database -  key = $(message.key}, messageKey = ${msg.messageKey}, messageText= ${msg.messageText}") }
    }
  }
  
    companion object {
      // Singleton prevents multiple instances of database opening at the
      // same time.
      @Volatile
      private var INSTANCE: MessageRoomDatabase? = null

      fun getDatabase(context: Context, scope : CoroutineScope): MessageRoomDatabase {
        // if the INSTANCE is not null, then return it,
        // if it is, then create the database
        return INSTANCE ?: synchronized(this) {
          val instance = Room.databaseBuilder(
            context.applicationContext,
            MessageRoomDatabase::class.java,
            "Message_database"
          ).addCallback(MessageDatabaseCallback(scope))
           .build()
          INSTANCE = instance
          // return instance
          instance
        }
      }
    }
  }