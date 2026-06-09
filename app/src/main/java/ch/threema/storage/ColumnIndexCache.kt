package ch.threema.storage

import android.database.Cursor
import androidx.collection.SimpleArrayMap
import ch.threema.app.BuildConfig

class ColumnIndexCache {
    private val indexMap = SimpleArrayMap<String, Int>()

    fun getColumnIndex(cursor: Cursor, columnName: String): Int {
        synchronized(indexMap) {
            val cachedIndex = indexMap[columnName]
            if (cachedIndex != null) {
                return cachedIndex
            }
            if (BuildConfig.DEBUG) {
                require(cursor.columnNames.count { it == columnName } <= 1) {
                    "Duplicate column name '$columnName' found in cursor"
                }
            }
            val index = cursor.getColumnIndex(columnName)
            indexMap.put(columnName, index)
            return index
        }
    }

    fun clear() {
        synchronized(indexMap) {
            indexMap.clear()
        }
    }
}
