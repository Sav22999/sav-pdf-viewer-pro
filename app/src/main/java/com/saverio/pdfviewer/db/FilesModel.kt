package com.saverio.pdfviewer.db

class FilesModel(
    var id: String = "",
    var date: String = "",
    var lastUpdate: String = "",
    var path: String = "",
    var lastPage: Int = 0,
    var scrollMode: String = "VERTICAL_TOP_TO_BOTTOM",
    var singlePage: Boolean = false,
    var nightMode: Boolean = false,
    var zoom: Float = 1.0F,
    var rotationLocked: Boolean = false,
    var notes: String = ""
)