package app.automa.chat

import org.json.JSONArray
import org.json.JSONObject

/** The objects of a JSON array; none for null. */
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** The strings of a JSON array; none for null. */
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }

/** The field [name] as text, or [fallback] when it is missing or null (Android `optString` gives "null" for null). */
fun JSONObject.str(name: String, fallback: String = ""): String = if (isNull(name)) fallback else optString(name, fallback)
