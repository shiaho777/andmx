package com.andmx.ui2.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

object FileTypeIcons {
    private val iconMap = mapOf(
        "kt" to Icons.Outlined.Code,
        "java" to Icons.Outlined.Code,
        "py" to Icons.Outlined.Code,
        "js" to Icons.Outlined.Code,
        "ts" to Icons.Outlined.Code,
        "go" to Icons.Outlined.Code,
        "rs" to Icons.Outlined.Code,
        "c" to Icons.Outlined.Code,
        "cpp" to Icons.Outlined.Code,
        "h" to Icons.Outlined.Code,
        "swift" to Icons.Outlined.Code,
        
        "json" to Icons.Outlined.DataObject,
        "xml" to Icons.Outlined.DataObject,
        "yaml" to Icons.Outlined.DataObject,
        "yml" to Icons.Outlined.DataObject,
        "toml" to Icons.Outlined.DataObject,
        
        "md" to Icons.Outlined.Description,
        "txt" to Icons.Outlined.Description,
        "pdf" to Icons.Outlined.PictureAsPdf,
        "doc" to Icons.Outlined.Description,
        "docx" to Icons.Outlined.Description,
        
        "png" to Icons.Outlined.Image,
        "jpg" to Icons.Outlined.Image,
        "jpeg" to Icons.Outlined.Image,
        "gif" to Icons.Outlined.Image,
        "svg" to Icons.Outlined.Image,
        
        "zip" to Icons.Outlined.FolderZip,
        "tar" to Icons.Outlined.FolderZip,
        "gz" to Icons.Outlined.FolderZip,
        
        "gradle" to Icons.Outlined.Build,
        "kts" to Icons.Outlined.Build,
        "sh" to Icons.Outlined.Terminal
    )
    
    fun iconFor(filename: String): ImageVector {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return iconMap[extension] ?: Icons.Outlined.InsertDriveFile
    }
    
    fun folderIcon(): ImageVector = Icons.Outlined.Folder
}
