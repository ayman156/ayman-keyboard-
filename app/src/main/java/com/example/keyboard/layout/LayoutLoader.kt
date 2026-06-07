package com.example.keyboard.layout

import android.content.Context
import android.util.Log
import com.example.keyboard.model.KeyType
import com.example.keyboard.model.KeyboardKey
import com.example.keyboard.model.KeyboardLayout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object LayoutLoader {
    private const val TAG = "LayoutLoader"

    fun loadLayout(context: Context, resourceId: Int): KeyboardLayout? {
        return try {
            val inputStream = context.resources.openRawResource(resourceId)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.use { it.readText() }

            val jsonObject = JSONObject(jsonString)
            val languageCode = jsonObject.getString("languageCode")
            val languageName = jsonObject.getString("languageName")
            val rowsArray = jsonObject.getJSONArray("rows")

            val rows = mutableListOf<List<KeyboardKey>>()
            for (i in 0 until rowsArray.length()) {
                val rowArray = rowsArray.getJSONArray(i)
                val row = mutableListOf<KeyboardKey>()
                for (j in 0 until rowArray.length()) {
                    val keyObj = rowArray.getJSONObject(j)
                    val label = keyObj.getString("label")
                    val typeStr = keyObj.getString("type")
                    val type = try {
                        KeyType.valueOf(typeStr)
                    } catch (e: Exception) {
                        KeyType.UNKNOWN
                    }
                    val output = if (keyObj.has("output")) keyObj.getString("output") else null

                    row.add(KeyboardKey(label = label, type = type, output = output))
                }
                rows.add(row)
            }

            KeyboardLayout(languageCode, languageName, rows)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading layout: ${e.message}", e)
            null
        }
    }
}
