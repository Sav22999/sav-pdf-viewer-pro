package com.saverio.pdfviewer.db

class BookmarksModel(
    var id: Int? = null,
    var date: String = "",
    var file: String = "", //the id of the FileModel linked
    var page: Int = 0,
    var notes: String = ""
)