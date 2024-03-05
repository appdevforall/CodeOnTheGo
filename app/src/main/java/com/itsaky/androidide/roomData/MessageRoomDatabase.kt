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
import com.itsaky.androidide.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray

// Annotates class to be a Room Database with a table (entity) of the Message class
  @Database(entities = arrayOf(Message::class), version = 1, exportSchema = false)
  abstract class MessageRoomDatabase : RoomDatabase() {

    abstract fun MessageDao(): MessageDao

  private class MessageDatabaseCallback(
    private val context : Context,
    private val scope: CoroutineScope
  ) : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
      super.onCreate(db)
      INSTANCE?.let { database ->
        scope.launch {
          populateDatabase(context, database.MessageDao())
        }
      }
    }

    suspend fun populateDatabase(context: Context, messageDao: MessageDao) {
      // Delete all content here.
      messageDao.deleteAll()
      try {
        val userList: JSONArray =
          context.resources.openRawResource(R.raw.help_messages).bufferedReader().use {
            JSONArray(it.readText())
          }

        userList.takeIf { it.length() > 0 }?.let { list ->
          for (index in 0 until list.length()) {
            val userObj = list.getJSONObject(index)
            messageDao.insert(
              Message(0, userObj.getString("id"), userObj.getString("text")))

          }
          Log.e("User App", "successfully pre-populated users into database")
        }
      } catch (exception: Exception) {
        Log.e(
          "User App",
          exception.localizedMessage ?: "failed to pre-populate users into database"
        )
      }
    val messageList : List<Message> = messageDao.getAlphabetizedMessages()
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
          ).addCallback(MessageDatabaseCallback(context, scope))
           .build()
          INSTANCE = instance
          // return instance
          instance
        }
      }
    }
  }