package com.andmx.agent.workflow

/** 单元测试用的内存文件系统：`list` 回的是**文件名**，与 `GuestFs.list` 同约定。 */
class InMemoryWorkflowFileSystem(initial: Map<String, String> = emptyMap()) : WorkflowFileSystem {

    val files: MutableMap<String, String> = initial.toMutableMap()
    private val directories: MutableSet<String> = mutableSetOf("/")

    init {
        initial.keys.forEach(::registerParents)
    }

    fun addDirectory(path: String) {
        directories += path.trimEnd('/')
        registerParents(path)
    }

    override fun exists(path: String): Boolean =
        files.containsKey(path) || isDirectory(path)

    override fun isDirectory(path: String): Boolean = directories.contains(path.trimEnd('/'))

    override fun readText(path: String): String =
        files[path] ?: error("ENOENT: no such file or directory, open '$path'")

    override fun writeText(path: String, content: String) {
        files[path] = content
        registerParents(path)
    }

    override fun list(dir: String): List<String> {
        val base = dir.trimEnd('/')
        if (!isDirectory(base)) error("ENOENT: no such directory, scandir '$dir'")
        val prefix = "$base/"
        return files.keys
            .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .map { it.removePrefix(prefix) }
            .sorted()
    }

    private fun registerParents(path: String) {
        var index = path.lastIndexOf('/')
        while (index > 0) {
            directories += path.substring(0, index)
            index = path.lastIndexOf('/', index - 1)
        }
    }
}
