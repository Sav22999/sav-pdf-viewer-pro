package com.saverio.pdfviewer.db

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

class DatabaseHandler(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(database: SQLiteDatabase) {
        //println("onCreate")
        var query = ""
        //Create "files" table
        query = "CREATE TABLE `${TABLE_NAME_FILES}` (" +
                "  `${COLUMN_ID_PK_FILES}` VARCHAR(100) NOT NULL PRIMARY KEY," +
                "  `${COLUMN_DATE_FILES}` TEXT NOT NULL," +
                "  `${COLUMN_LAST_UPDATE_FILES}` TEXT NOT NULL," +
                "  `${COLUMN_FILE_PATH_FILES}` TEXT NOT NULL," +
                "  `${COLUMN_LAST_PAGE_FILES}` INTEGER NOT NULL," +
                "  `${COLUMN_SCROLL_MODE_FILES}` TEXT NOT NULL DEFAULT 'VERTICAL_TOP_TO_BOTTOM'," +
                "  `${COLUMN_SINGLE_PAGE_FILES}` INTEGER NOT NULL DEFAULT 0," +
                "  `${COLUMN_NIGHT_MODE_FILES}` INTEGER NOT NULL DEFAULT 0," +
                "  `${COLUMN_ZOOM_FILES}` REAL NOT NULL DEFAULT 1.0," +
                "  `${COLUMN_ROTATION_LOCKED_FILES}` INTEGER NOT NULL DEFAULT 0," +
                "  `${COLUMN_NOTES_FILES}` TEXT NOT NULL" +
                ")"
        database.execSQL(query)

        //Create "bookmarks" table
        query = "CREATE TABLE `${TABLE_NAME_BOOKMARKS}` (" +
                "  `${COLUMN_ID_PK_BOOKMARKS}` INTEGER NOT NULL PRIMARY KEY," +
                "  `${COLUMN_DATE_FILES}` TEXT NOT NULL," +
                "  `${COLUMN_FILE_FK_BOOKMARKS}` VARCHAR(100) NOT NULL," +
                "  `${COLUMN_PAGE_BOOKMARKS}` INTEGER NOT NULL," +
                "  `${COLUMN_NOTES_BOOKMARKS}` TEXT NOT NULL" +
                ")"
        database.execSQL(query)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Keep existing data and migrate incrementally.
        if (oldVersion < 3) {
            addColumnIfMissing(
                database,
                TABLE_NAME_FILES,
                COLUMN_SCROLL_MODE_FILES,
                "TEXT NOT NULL DEFAULT 'VERTICAL_TOP_TO_BOTTOM'"
            )
            addColumnIfMissing(
                database,
                TABLE_NAME_FILES,
                COLUMN_SINGLE_PAGE_FILES,
                "INTEGER NOT NULL DEFAULT 0"
            )
            addColumnIfMissing(
                database,
                TABLE_NAME_FILES,
                COLUMN_NIGHT_MODE_FILES,
                "INTEGER NOT NULL DEFAULT 0"
            )
            addColumnIfMissing(
                database,
                TABLE_NAME_FILES,
                COLUMN_ZOOM_FILES,
                "REAL NOT NULL DEFAULT 1.0"
            )
            addColumnIfMissing(
                database,
                TABLE_NAME_FILES,
                COLUMN_ROTATION_LOCKED_FILES,
                "INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private fun addColumnIfMissing(
        database: SQLiteDatabase,
        tableName: String,
        columnName: String,
        columnDefinition: String
    ) {
        if (!hasColumn(database, tableName, columnName)) {
            database.execSQL(
                "ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDefinition"
            )
        }
    }

    private fun hasColumn(database: SQLiteDatabase, tableName: String, columnName: String): Boolean {
        var cursor: Cursor? = null
        return try {
            cursor = database.rawQuery("PRAGMA table_info(`$tableName`)", null)
            var found = false
            if (cursor.moveToFirst()) {
                do {
                    val currentColumn = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    if (currentColumn == columnName) {
                        found = true
                        break
                    }
                } while (cursor.moveToNext())
            }
            found
        } finally {
            cursor?.close()
        }
    }


    //Files
    fun add(file: FilesModel): Long {
        val database = writableDatabase

        val contentValues = ContentValues()
        contentValues.put(COLUMN_ID_PK_FILES, file.id)
        contentValues.put(COLUMN_DATE_FILES, file.date)
        contentValues.put(COLUMN_LAST_UPDATE_FILES, file.lastUpdate)
        contentValues.put(COLUMN_LAST_PAGE_FILES, file.lastPage)
        contentValues.put(COLUMN_FILE_PATH_FILES, file.path)
        contentValues.put(COLUMN_SCROLL_MODE_FILES, file.scrollMode)
        contentValues.put(COLUMN_SINGLE_PAGE_FILES, if (file.singlePage) 1 else 0)
        contentValues.put(COLUMN_NIGHT_MODE_FILES, if (file.nightMode) 1 else 0)
        contentValues.put(COLUMN_ZOOM_FILES, file.zoom)
        contentValues.put(COLUMN_ROTATION_LOCKED_FILES, if (file.rotationLocked) 1 else 0)
        contentValues.put(COLUMN_NOTES_FILES, file.notes)
        val success = database.insert(TABLE_NAME_FILES, null, contentValues)
        database.close()

        //println("Added ${file.id}")

        return success
    }

    @SuppressLint("Range")
    fun getFiles(id: String? = null): ArrayList<FilesModel> {
        val filesList = ArrayList<FilesModel>()
        var query = "SELECT * FROM `${TABLE_NAME_FILES}`"
        if (id != null)
            query = "SELECT * FROM `${TABLE_NAME_FILES}` WHERE `${COLUMN_ID_PK_FILES}`='$id'"

        val database = readableDatabase
        var cursor: Cursor? = null

        try {
            cursor = database.rawQuery(query, null)
        } catch (e: SQLException) {
            database.execSQL(query)
            return ArrayList()
        }

        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getString(cursor.getColumnIndex(COLUMN_ID_PK_FILES))
                val date = cursor.getString(cursor.getColumnIndex(COLUMN_DATE_FILES))
                val lastUpdate = cursor.getString(cursor.getColumnIndex(COLUMN_LAST_UPDATE_FILES))
                val path = cursor.getString(cursor.getColumnIndex(COLUMN_FILE_PATH_FILES))
                val lastPage = cursor.getInt(cursor.getColumnIndex(COLUMN_LAST_PAGE_FILES))
                val scrollMode =
                    cursor.getString(cursor.getColumnIndex(COLUMN_SCROLL_MODE_FILES))
                        ?: "VERTICAL_TOP_TO_BOTTOM"
                val singlePage = cursor.getInt(cursor.getColumnIndex(COLUMN_SINGLE_PAGE_FILES)) == 1
                val nightMode = cursor.getInt(cursor.getColumnIndex(COLUMN_NIGHT_MODE_FILES)) == 1
                val zoom = cursor.getFloat(cursor.getColumnIndex(COLUMN_ZOOM_FILES))
                val rotationLocked =
                    cursor.getInt(cursor.getColumnIndex(COLUMN_ROTATION_LOCKED_FILES)) == 1
                val notes = cursor.getString(cursor.getColumnIndex(COLUMN_NOTES_FILES))

                val fileToAdd = FilesModel(
                    id = id,
                    date = date,
                    lastUpdate = lastUpdate,
                    path = path,
                    lastPage = lastPage,
                    scrollMode = scrollMode,
                    singlePage = singlePage,
                    nightMode = nightMode,
                    zoom = zoom,
                    rotationLocked = rotationLocked,
                    notes = notes
                )

                //println("Get ${id}")

                filesList.add(fileToAdd)
            } while (cursor.moveToNext())
        }
        database.close()
        return filesList
    }

    fun updateFile(file: FilesModel): Int {
        val database = writableDatabase

        val contentValues = ContentValues()
        contentValues.put(COLUMN_ID_PK_FILES, file.id)
        contentValues.put(COLUMN_DATE_FILES, file.date)
        contentValues.put(COLUMN_LAST_UPDATE_FILES, file.lastUpdate)
        contentValues.put(COLUMN_FILE_PATH_FILES, file.path)
        contentValues.put(COLUMN_LAST_PAGE_FILES, file.lastPage)
        contentValues.put(COLUMN_SCROLL_MODE_FILES, file.scrollMode)
        contentValues.put(COLUMN_SINGLE_PAGE_FILES, if (file.singlePage) 1 else 0)
        contentValues.put(COLUMN_NIGHT_MODE_FILES, if (file.nightMode) 1 else 0)
        contentValues.put(COLUMN_ZOOM_FILES, file.zoom)
        contentValues.put(COLUMN_ROTATION_LOCKED_FILES, if (file.rotationLocked) 1 else 0)
        contentValues.put(COLUMN_NOTES_FILES, file.notes)

        val success =
            database.update(
                TABLE_NAME_FILES,
                contentValues,
                "`$COLUMN_ID_PK_FILES` = '${file.id}'",
                null
            ) //we need the primary key to update a record
        database.close()

        //println("Updated ${file.id}")

        return success
    }

    fun deleteFile(id: String): Int {
        val database = writableDatabase
        val contentValues = ContentValues()

        val success = database.delete(
            TABLE_NAME_FILES,
            "$COLUMN_ID_PK_FILES = '$id'",
            null
        )
        database.close()
        return success
    }

    fun checkFile(id: String): Boolean {
        var returnValue = false
        val query =
            "SELECT * FROM `${TABLE_NAME_FILES}` WHERE `${COLUMN_ID_PK_FILES}` = '$id'"

        val database = readableDatabase
        var cursor: Cursor? = null

        try {
            cursor = database.rawQuery(query, null)
        } catch (e: SQLException) {
            database.execSQL(query)
            return returnValue
        }

        if (cursor.moveToFirst()) {
            returnValue = true
        }
        //println("Exist YES/NO: ${returnValue.toString()}")

        database.close()
        return returnValue
    }
    //End || Files

    //Bookmarks
    fun add(bookmark: BookmarksModel): Long {
        val database = writableDatabase

        val contentValues = ContentValues()
        contentValues.put(COLUMN_ID_PK_BOOKMARKS, getNewIdBookmarks())
        contentValues.put(COLUMN_DATE_BOOKMARKS, bookmark.date)
        contentValues.put(COLUMN_PAGE_BOOKMARKS, bookmark.page)
        contentValues.put(COLUMN_FILE_FK_BOOKMARKS, bookmark.file)
        contentValues.put(COLUMN_NOTES_FILES, bookmark.notes)
        val success = database.insert(TABLE_NAME_BOOKMARKS, null, contentValues)
        database.close()

        //println("Added ${bookmark.id} || file: ${bookmark.file}")

        return success
    }

    @SuppressLint("Range")
    fun getBookmarks(fileId: String, page: Int? = null): ArrayList<BookmarksModel> {
        //get all bookmarks from a specific file (or a specific bookmark-id)
        val filesList = ArrayList<BookmarksModel>()
        var query =
            "SELECT * FROM `${TABLE_NAME_BOOKMARKS}` WHERE `${COLUMN_FILE_FK_BOOKMARKS}`='$fileId' ORDER BY `${COLUMN_PAGE_BOOKMARKS}` ASC"
        if (page != null)
            query =
                "SELECT * FROM `${TABLE_NAME_BOOKMARKS}` WHERE `${COLUMN_FILE_FK_BOOKMARKS}`='$fileId' AND `${COLUMN_PAGE_BOOKMARKS}`='$page' ORDER BY `${COLUMN_PAGE_BOOKMARKS}` ASC"

        val database = readableDatabase
        var cursor: Cursor? = null

        try {
            cursor = database.rawQuery(query, null)
        } catch (e: SQLException) {
            database.execSQL(query)
            return ArrayList()
        }

        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getInt(cursor.getColumnIndex(COLUMN_ID_PK_BOOKMARKS))
                val date = cursor.getString(cursor.getColumnIndex(COLUMN_DATE_BOOKMARKS))
                val fileId = cursor.getString(cursor.getColumnIndex(COLUMN_FILE_FK_BOOKMARKS))
                val page = cursor.getInt(cursor.getColumnIndex(COLUMN_PAGE_BOOKMARKS))
                val notes = cursor.getString(cursor.getColumnIndex(COLUMN_NOTES_BOOKMARKS))

                val fileToAdd = BookmarksModel(
                    id = id!!,
                    date = date,
                    file = fileId,
                    page = page,
                    notes = notes
                )

                //println("Get $id from $fileId")

                filesList.add(fileToAdd)
            } while (cursor.moveToNext())
        }
        database.close()
        return filesList
    }

    fun updateBookmark(bookmark: BookmarksModel): Int {
        val database = writableDatabase

        val contentValues = ContentValues()
        contentValues.put(COLUMN_ID_PK_BOOKMARKS, bookmark.id)
        contentValues.put(COLUMN_DATE_BOOKMARKS, bookmark.date)
        contentValues.put(COLUMN_PAGE_BOOKMARKS, bookmark.page)
        contentValues.put(COLUMN_FILE_FK_BOOKMARKS, bookmark.file)
        contentValues.put(COLUMN_NOTES_FILES, bookmark.notes)

        val success =
            database.update(
                TABLE_NAME_BOOKMARKS,
                contentValues,
                "`$COLUMN_ID_PK_BOOKMARKS` = '${bookmark.id}'",
                null
            ) //we need the primary key to update a record
        database.close()

        //println("Updated ${bookmark.id} || file: ${bookmark.file}")

        return success
    }

    fun deleteBookmark(id: Int): Int {
        val database = writableDatabase
        val contentValues = ContentValues()

        val success = database.delete(
            TABLE_NAME_BOOKMARKS,
            "$COLUMN_ID_PK_BOOKMARKS = '$id'",
            null
        )
        database.close()
        return success
    }

    fun checkBookmark(fileId: String, page: Int): Boolean {
        var returnValue = false
        val query =
            "SELECT * FROM `${TABLE_NAME_BOOKMARKS}` WHERE `${COLUMN_FILE_FK_BOOKMARKS}` = '$fileId' AND `${COLUMN_PAGE_BOOKMARKS}` = '$page'"

        val database = readableDatabase
        var cursor: Cursor? = null

        try {
            cursor = database.rawQuery(query, null)
        } catch (e: SQLException) {
            database.execSQL(query)
            return returnValue
        }

        if (cursor.moveToFirst()) {
            returnValue = true
        }
        //println("Exist bookmark YES/NO: ${returnValue.toString()}")

        database.close()
        return returnValue
    }

    @SuppressLint("Range")
    private fun getNewIdBookmarks(): Int {
        //get a new unique id for bookmarks (based to the last one created)
        var valueToReturn = 0
        val query =
            "SELECT * FROM `${TABLE_NAME_BOOKMARKS}` ORDER BY `${COLUMN_ID_PK_BOOKMARKS}` DESC LIMIT 1"
        val database = readableDatabase
        var cursor: Cursor? = null

        try {
            cursor = database.rawQuery(query, null)
        } catch (e: SQLException) {
            database.execSQL(query)
            return valueToReturn
        }

        if (cursor.moveToFirst()) {
            valueToReturn = cursor.getInt(cursor.getColumnIndex(COLUMN_ID_PK_BOOKMARKS)) + 1
        }
        return valueToReturn
    }
    //End || Bookmarks

    //TODO: just for testings
    fun deleteAllFiles() {
        val database = writableDatabase

        val success = database.delete(
            TABLE_NAME_FILES,
            null,
            null
        )
        database.close()
    }

    fun deleteAllBookmarks() {
        val database = writableDatabase

        val success = database.delete(
            TABLE_NAME_BOOKMARKS,
            null,
            null
        )
        database.close()
    }

    companion object {
        //general
        private val DATABASE_NAME = "PDFFiles"
        private val DATABASE_VERSION = 3 //TODO: change this manually

        //"files" table
        val TABLE_NAME_FILES = "files"
        val COLUMN_ID_PK_FILES = "id"
        val COLUMN_FILE_PATH_FILES = "path"
        val COLUMN_DATE_FILES = "date"
        val COLUMN_LAST_UPDATE_FILES = "last_update"
        val COLUMN_LAST_PAGE_FILES = "page"
        val COLUMN_SCROLL_MODE_FILES = "scroll_mode"
        val COLUMN_SINGLE_PAGE_FILES = "single_page"
        val COLUMN_NIGHT_MODE_FILES = "night_mode"
        val COLUMN_ZOOM_FILES = "zoom"
        val COLUMN_ROTATION_LOCKED_FILES = "rotation_locked"
        val COLUMN_NOTES_FILES = "notes"

        //"bookmarks" table
        val TABLE_NAME_BOOKMARKS = "bookmarks"
        val COLUMN_ID_PK_BOOKMARKS = "id"
        val COLUMN_FILE_FK_BOOKMARKS = "file_id"
        val COLUMN_DATE_BOOKMARKS = "date"
        val COLUMN_PAGE_BOOKMARKS = "page"
        val COLUMN_NOTES_BOOKMARKS = "notes"
    }
}