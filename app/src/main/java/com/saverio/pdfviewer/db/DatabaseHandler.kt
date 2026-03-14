package com.saverio.pdfviewer.db

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.collections.ArrayList

class DatabaseHandler(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(database: SQLiteDatabase) {
        val createFilesQuery = "CREATE TABLE `${TABLE_NAME_FILES}` (" +
                "  `${COLUMN_ID_PK_FILES}` VARCHAR(100) NOT NULL PRIMARY KEY," +
                "  `${COLUMN_DATE_FILES}` TEXT NOT NULL," +
                "  `${COLUMN_LAST_UPDATE_FILES}` TEXT NOT NULL," +
                "  `${COLUMN_FILE_PATH_FILES}` TEXT NOT NULL," +
                "  `${COLUMN_LAST_PAGE_FILES}` INTEGER NOT NULL," +
                "  `${COLUMN_SCROLL_MODE_FILES}` TEXT NOT NULL DEFAULT 'VERTICAL_TOP_TO_BOTTOM'," +
                "  `${COLUMN_SINGLE_PAGE_FILES}` INTEGER NOT NULL DEFAULT 0," +
                "  `${COLUMN_NIGHT_MODE_FILES}` INTEGER NOT NULL DEFAULT 0," +
                "  `${COLUMN_CONTRAST_OVERLAY_FILES}` INTEGER NOT NULL DEFAULT 0," +
                "  `${COLUMN_ZOOM_FILES}` REAL NOT NULL DEFAULT 1.0," +
                "  `${COLUMN_ROTATION_LOCKED_FILES}` INTEGER NOT NULL DEFAULT 0," +
                "  `${COLUMN_FULLSCREEN_FILES}` INTEGER NOT NULL DEFAULT 0," +
                "  `${COLUMN_NOTES_FILES}` TEXT NOT NULL" +
                ")"
        database.execSQL(createFilesQuery)

        val createBookmarksQuery = "CREATE TABLE `${TABLE_NAME_BOOKMARKS}` (" +
                "  `${COLUMN_ID_PK_BOOKMARKS}` INTEGER NOT NULL PRIMARY KEY," +
                "  `${COLUMN_DATE_FILES}` TEXT NOT NULL," +
                "  `${COLUMN_FILE_FK_BOOKMARKS}` VARCHAR(100) NOT NULL," +
                "  `${COLUMN_PAGE_BOOKMARKS}` INTEGER NOT NULL," +
                "  `${COLUMN_NOTES_BOOKMARKS}` TEXT NOT NULL" +
                ")"
        database.execSQL(createBookmarksQuery)
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
        if (oldVersion < 4) {
            addColumnIfMissing(
                database,
                TABLE_NAME_FILES,
                COLUMN_CONTRAST_OVERLAY_FILES,
                "INTEGER NOT NULL DEFAULT 0"
            )
            addColumnIfMissing(
                database,
                TABLE_NAME_FILES,
                COLUMN_FULLSCREEN_FILES,
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
        contentValues.put(COLUMN_CONTRAST_OVERLAY_FILES, if (file.contrastOverlay) 1 else 0)
        contentValues.put(COLUMN_ZOOM_FILES, file.zoom)
        contentValues.put(COLUMN_ROTATION_LOCKED_FILES, if (file.rotationLocked) 1 else 0)
        contentValues.put(COLUMN_FULLSCREEN_FILES, if (file.fullscreen) 1 else 0)
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
        val cursor = try {
            database.rawQuery(query, null)
        } catch (_: SQLException) {
            database.execSQL(query)
            return ArrayList()
        }

        cursor.use {
            if (it.moveToFirst()) {
                do {
                    val storedId = it.getString(it.getColumnIndex(COLUMN_ID_PK_FILES))
                    val date = it.getString(it.getColumnIndex(COLUMN_DATE_FILES))
                    val lastUpdate = it.getString(it.getColumnIndex(COLUMN_LAST_UPDATE_FILES))
                    val path = it.getString(it.getColumnIndex(COLUMN_FILE_PATH_FILES))
                    val lastPage = it.getInt(it.getColumnIndex(COLUMN_LAST_PAGE_FILES))
                    val scrollMode =
                        it.getString(it.getColumnIndex(COLUMN_SCROLL_MODE_FILES))
                            ?: "VERTICAL_TOP_TO_BOTTOM"
                    val singlePage = it.getInt(it.getColumnIndex(COLUMN_SINGLE_PAGE_FILES)) == 1
                    val nightMode = it.getInt(it.getColumnIndex(COLUMN_NIGHT_MODE_FILES)) == 1
                    val contrastOverlay = it.getInt(it.getColumnIndex(COLUMN_CONTRAST_OVERLAY_FILES)) == 1
                    val zoom = it.getFloat(it.getColumnIndex(COLUMN_ZOOM_FILES))
                    val rotationLocked =
                        it.getInt(it.getColumnIndex(COLUMN_ROTATION_LOCKED_FILES)) == 1
                    val fullscreen = it.getInt(it.getColumnIndex(COLUMN_FULLSCREEN_FILES)) == 1
                    val notes = it.getString(it.getColumnIndex(COLUMN_NOTES_FILES))

                    filesList.add(
                        FilesModel(
                            id = storedId,
                            date = date,
                            lastUpdate = lastUpdate,
                            path = path,
                            lastPage = lastPage,
                            scrollMode = scrollMode,
                            singlePage = singlePage,
                            nightMode = nightMode,
                            contrastOverlay = contrastOverlay,
                            zoom = zoom,
                            rotationLocked = rotationLocked,
                            fullscreen = fullscreen,
                            notes = notes
                        )
                    )
                } while (it.moveToNext())
            }
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
        contentValues.put(COLUMN_CONTRAST_OVERLAY_FILES, if (file.contrastOverlay) 1 else 0)
        contentValues.put(COLUMN_ZOOM_FILES, file.zoom)
        contentValues.put(COLUMN_ROTATION_LOCKED_FILES, if (file.rotationLocked) 1 else 0)
        contentValues.put(COLUMN_FULLSCREEN_FILES, if (file.fullscreen) 1 else 0)
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

    @Suppress("unused")
    fun deleteFile(id: String): Int {
        val database = writableDatabase

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
        val cursor = try {
            database.rawQuery(query, null)
        } catch (_: SQLException) {
            database.execSQL(query)
            return returnValue
        }

        cursor.use {
            if (it.moveToFirst()) {
                returnValue = true
            }
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
        val cursor = try {
            database.rawQuery(query, null)
        } catch (_: SQLException) {
            database.execSQL(query)
            return ArrayList()
        }

        cursor.use {
            if (it.moveToFirst()) {
                do {
                    val bookmarkId = it.getInt(it.getColumnIndex(COLUMN_ID_PK_BOOKMARKS))
                    val date = it.getString(it.getColumnIndex(COLUMN_DATE_BOOKMARKS))
                    val storedFileId = it.getString(it.getColumnIndex(COLUMN_FILE_FK_BOOKMARKS))
                    val storedPage = it.getInt(it.getColumnIndex(COLUMN_PAGE_BOOKMARKS))
                    val notes = it.getString(it.getColumnIndex(COLUMN_NOTES_BOOKMARKS))

                    filesList.add(
                        BookmarksModel(
                            id = bookmarkId,
                            date = date,
                            file = storedFileId,
                            page = storedPage,
                            notes = notes
                        )
                    )
                } while (it.moveToNext())
            }
        }
        database.close()
        return filesList
    }

    @Suppress("unused")
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
        val cursor = try {
            database.rawQuery(query, null)
        } catch (_: SQLException) {
            database.execSQL(query)
            return returnValue
        }

        cursor.use {
            if (it.moveToFirst()) {
                returnValue = true
            }
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
        val cursor = try {
            database.rawQuery(query, null)
        } catch (_: SQLException) {
            database.execSQL(query)
            return valueToReturn
        }

        cursor.use {
            if (it.moveToFirst()) {
                valueToReturn = it.getInt(it.getColumnIndex(COLUMN_ID_PK_BOOKMARKS)) + 1
            }
        }
        return valueToReturn
    }
    //End || Bookmarks

    //TODO: just for testings
    @Suppress("unused")
    fun deleteAllFiles() {
        val database = writableDatabase

        database.delete(
            TABLE_NAME_FILES,
            null,
            null
        )
        database.close()
    }

    @Suppress("unused")
    fun deleteAllBookmarks() {
        val database = writableDatabase

        database.delete(
            TABLE_NAME_BOOKMARKS,
            null,
            null
        )
        database.close()
    }

    companion object {
        //general
        private val DATABASE_NAME = "PDFFiles"
        private val DATABASE_VERSION = 4 //TODO: change this manually

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
        val COLUMN_CONTRAST_OVERLAY_FILES = "contrast_overlay"
        val COLUMN_ZOOM_FILES = "zoom"
        val COLUMN_ROTATION_LOCKED_FILES = "rotation_locked"
        val COLUMN_FULLSCREEN_FILES = "fullscreen"
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