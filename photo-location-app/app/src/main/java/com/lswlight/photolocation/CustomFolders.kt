package com.lswlight.photolocation

import android.content.Context
import java.io.File

/** 내 폴더 이름 앞에 붙는 표시. 목록·내보내기 폴더 이름에 함께 쓴다. */
const val CUSTOM_PREFIX = "⭐ "

/**
 * 사용자가 직접 만든 폴더와, 각 폴더에 넣은 사진 id 를 저장한다.
 * 내 폴더에 넣은 사진은 자동 분류(집·회사·지역·앱)에서 빠진다.
 *
 * 파일 형식 (탭 구분): "F\t폴더이름" / "P\t사진id\t폴더이름"
 */
class CustomFolderStore(context: Context) {

    private val file = File(context.filesDir, "custom_folders.tsv")
    private val folders = mutableListOf<String>()
    private val assignments = HashMap<Long, String>()

    init {
        if (file.exists()) {
            file.forEachLine { line ->
                val p = line.split('\t')
                when {
                    p.size == 2 && p[0] == "F" -> folders += p[1]
                    p.size == 3 && p[0] == "P" -> p[1].toLongOrNull()?.let { assignments[it] = p[2] }
                }
            }
        }
    }

    val folderNames: List<String> get() = folders.toList()

    /** 사진 id → 폴더 이름 */
    val assigned: Map<Long, String> get() = assignments

    /** 새 폴더를 만든다. 이름이 비었거나 이미 있으면 false */
    fun create(rawName: String): Boolean {
        val name = clean(rawName)
        if (name.isEmpty() || name in folders) return false
        folders += name
        save()
        return true
    }

    /** 폴더를 지운다. 안에 있던 사진은 다시 자동 분류로 돌아간다. */
    fun delete(name: String) {
        folders -= name
        assignments.values.removeAll { it == name }
        save()
    }

    fun add(name: String, ids: Collection<Long>) {
        if (name !in folders) return
        ids.forEach { assignments[it] = name }
        save()
    }

    fun remove(ids: Collection<Long>) {
        ids.forEach { assignments.remove(it) }
        save()
    }

    private fun clean(name: String) = name.replace(Regex("[\\t\\r\\n]"), " ").trim()

    private fun save() {
        file.bufferedWriter().use { w ->
            folders.forEach { w.write("F\t$it\n") }
            assignments.forEach { (id, name) -> w.write("P\t$id\t$name\n") }
        }
    }
}
