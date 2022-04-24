package com.saverio.pdfviewer.db

class FilesModel(
    var id: String = "",
    var date: String = "",
    var lastUpdate: String = "",
    var path: String = "",
    var lastPage: Int = 0,
    var notes: String = ""
)