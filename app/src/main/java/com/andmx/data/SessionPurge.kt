package com.andmx.data

import java.io.File

fun purgePathsFor(filesDir: File, session: ConversationEntity): List<File> {
    val paths = mutableListOf<File>()
    if (session.rolloutPath.isNotBlank()) {
        paths += File(session.rolloutPath)
    }
    if (session.sessionId.isNotBlank()) {
        val name = session.sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jsonl"
        paths += File(File(filesDir, "topics"), name)
    }
    return paths.distinctBy { it.absolutePath }
}